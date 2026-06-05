package io.thisismo.vego.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTool
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.features.acp.AcpAgent
import ai.koog.agents.features.acp.withAcpAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.utils.time.KoogClock
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/**
 * The lifecycle phase a session is in. Each ACP turn (`prompt`) runs one phase to completion and
 * then ends the turn — that turn boundary *is* the "native HitL suspension". While the IDE shows
 * the streamed form/result and waits for the user, no agent compute is running: the session simply
 * holds its [phase] and the artifacts drafted so far, and the next `prompt` resumes from there.
 */
private enum class Phase {
    /** Workflow 1: raw idea → Hydration → BusinessAnalysis → clarifying-questions form. */
    INTAKE,

    /** Suspended after Workflow 2's RequirementsReview, waiting for the answered form. */
    AWAITING_REQUIREMENTS,

    /** Suspended after Workflow 4's ArchitectureReview, waiting for Approve / Reject. */
    AWAITING_ARCH_REVIEW,

    /** Workflow 4 teardown done: files committed, session may be closed by the client. */
    DONE,
}

/**
 * Per-stage models. Each workflow stage is matched to a model whose strengths fit the task:
 *
 *  - [businessAnalysisModel] — fast, highly conversational; drives intake and the first HitL form.
 *  - [technicalDesignModel] — a reasoning model for the heavy architecture specs (ADRs / OpenAPI / C4).
 *  - [validationModel] — fast again, for the quick verification checks and the second HitL review.
 *  - [teardownModel] — fast; only runs the deterministic git commit loop.
 *
 * Within a single agent run the active model is switched per node via [llm.writeSession].changeModel,
 * which persists the new model on the shared LLM context for all subsequent nodes and subgraphs.
 */
private val businessAnalysisModel = OpenAIModels.Chat.GPT4o
private val technicalDesignModel = OpenAIModels.Chat.O3Mini
private val validationModel = OpenAIModels.Chat.GPT4o
private val teardownModel = OpenAIModels.Chat.GPT4o

/**
 * A business-analyst / software-architect agent session driven over the Agent Client Protocol.
 *
 * It implements the four workflows as a phase machine across ACP turns:
 *  1. **Ingestion & Business Analysis** — hydrate domain context, decompose the idea into epics +
 *     acceptance criteria, and surface clarifying questions.
 *  2. **HitL suspension** — stream the questions to the IDE as a structured form and end the turn.
 *  3. **Compression & Technical Execution** — on the answered form, compress the conversation into
 *     dense facts (discarding raw chatter), then draft ADRs / OpenAPI / C4 to the filesystem.
 *  4. **Validation & Teardown** — lint the drafted docs via shell, get a final Approve/Reject, and
 *     on approval commit the files with git.
 */
class KoogAnalystSession(
    override val sessionId: SessionId,
    private val promptExecutor: PromptExecutor,
    private val protocol: Protocol,
    private val clock: KoogClock,
    private val workspaceRoot: String,
) : AgentSession {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /** Where drafted architecture documents are written. */
    private val docsDir: String = "$workspaceRoot/docs/architecture"

    // ---- State that survives between turns while the session is suspended ----
    private var phase: Phase = Phase.INTAKE
    private var requirementsDraft: RequirementsDraft? = null
    private var validationReport: ValidationReport? = null

    private var agentJob: Deferred<Unit>? = null
    private val agentMutex = Mutex()

    override suspend fun prompt(
        content: List<ContentBlock>,
        @Suppress("LocalVariableName") _meta: JsonElement?,
    ): Flow<Event> = channelFlow {
        val text = content.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
            .trim()

        logger.info { "Session $sessionId received prompt in phase $phase" }

        agentMutex.withLock {
            agentJob = async {
                when (phase) {
                    Phase.INTAKE -> runIntake(this@channelFlow, text)
                    Phase.AWAITING_REQUIREMENTS -> runTechnicalExecution(this@channelFlow, text)
                    Phase.AWAITING_ARCH_REVIEW -> runValidationAndTeardown(this@channelFlow, text)
                    Phase.DONE -> emitMessage(this@channelFlow, "This analysis session is complete. Start a new session to analyze another idea.")
                }
            }
            agentJob?.await()
        }
    }

    override suspend fun cancel() {
        logger.info { "Cancelling analyst session $sessionId" }
        agentJob?.cancelAndJoin()
    }

    // =====================================================================================
    // Workflow 1 & 2 — Ingestion, Business Analysis, and the first HitL suspension
    // =====================================================================================
    private suspend fun runIntake(producer: ProducerScope<Event>, idea: String) {
        val config = AIAgentConfig(
            prompt = prompt("ba-intake") {
                system(
                    """
                    You are a senior business analyst. You turn raw product ideas into formalized,
                    agent-driven requirements. You evaluate ideas against the existing domain
                    boundaries described in the provided project context, decompose them into epics
                    with concrete acceptance criteria, mark what is explicitly out of scope, and you
                    are rigorous about surfacing the clarifying questions that must be answered before
                    any technical design can begin. Never invent requirements the user did not imply.
                    """.trimIndent()
                )
            },
            // Business Analysis runs entirely on the fast, conversational model.
            model = businessAnalysisModel,
            maxAgentIterations = 50,
        )

        val strategy = strategy<String, Unit>("ba-intake") {
            // Hydration node — ground the analysis in retrieved domain context.
            val hydrate by node<String, String>("hydration") { rawIdea ->
                val context = hydrateDomainContext(workspaceRoot)
                """
                DOMAIN CONTEXT (retrieved from the local workspace):
                $context

                RAW IDEA SUBMITTED BY THE USER:
                $rawIdea

                Produce a RequirementsDraft: a short summary, the epics (each with acceptance
                criteria), out-of-scope items, and the clarifying questions you need answered before
                technical design. Prefer SINGLE_CHOICE / MULTI_CHOICE / BOOLEAN questions with
                explicit options where it helps the user answer quickly.
                """.trimIndent()
            }

            // BusinessAnalysis node — structured requirements + clarifying questions.
            val analyze by nodeLLMRequestStructured<RequirementsDraft>("business-analysis")

            // Output transition — persist the draft, stream the form, suspend the turn.
            val emitForm by node<RequirementsDraft, Unit>("requirements-review") { draft ->
                requirementsDraft = draft
                phase = Phase.AWAITING_REQUIREMENTS
                withAcpAgent {
                    sendEvent(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentMessageChunk(ContentBlock.Text(renderRequirementsForm(draft)))
                        )
                    )
                }
            }

            nodeStart then hydrate then analyze
            edge(analyze forwardTo emitForm transformed { it.getOrThrow().data })
            edge(emitForm forwardTo nodeFinish)
        }

        buildAgent(producer, config, strategy, intakeToolRegistry(), streamDefaults = false).run(idea)
    }

    // =====================================================================================
    // Workflow 3 & 4 (part 1) — Compression, Technical Design, Validation, second HitL suspension
    // =====================================================================================
    private suspend fun runTechnicalExecution(producer: ProducerScope<Event>, answers: String) {
        val draft = requirementsDraft
        if (draft == null) {
            emitMessage(producer, "No drafted requirements found for this session — please restart.")
            return
        }

        val config = AIAgentConfig(
            // Seed the prompt with the full preceding "chat": the approved draft and the questions
            // that were asked. This is exactly the raw history the compression interceptor will
            // distill into facts before the heavy design work begins.
            prompt = prompt("ba-design") {
                system(
                    """
                    You are a pragmatic software architect. Given approved requirements and the
                    user's answers, you produce architecture artifacts as files on disk: Architecture
                    Decision Records, an OpenAPI schema, and a C4 model. You write valid markdown with
                    working relative links and valid YAML. You use the provided file and shell tools;
                    you never fabricate file contents you did not write. Workspace root: $workspaceRoot.
                    Write all architecture documents under: $docsDir.
                    """.trimIndent()
                )
                user("APPROVED REQUIREMENTS DRAFT:\n${renderDraftText(draft)}")
                user("CLARIFYING QUESTIONS THAT WERE ASKED:\n${renderQuestions(draft)}")
            },
            // Base model for the cheap framing nodes (answer ingestion + history compression).
            // The graph then switches to the reasoning model for design and back for validation.
            model = businessAnalysisModel,
            maxAgentIterations = 200,
        )

        // The concepts the compression interceptor extracts rigid "Facts" about.
        val compression = FactRetrievalHistoryCompressionStrategy(
            Concept("requirements", "Hard product/business requirements, epics, and acceptance criteria agreed with the user", FactType.MULTIPLE),
            Concept("constraints", "Technical constraints, deprecations, non-goals, and explicit decisions stated by the user", FactType.MULTIPLE),
            Concept("domain", "Existing domain boundaries and integration points relevant to the design", FactType.MULTIPLE),
        )

        val strategy = strategy<String, Unit>("ba-design") {
            // Inject the answered form into the conversation history.
            val ingestAnswers by node<String, Unit>("ingest-answers") { userAnswers ->
                llm.writeSession {
                    appendPrompt { user("USER ANSWERS TO THE CLARIFYING QUESTIONS:\n$userAnswers") }
                }
            }

            // Compression Interceptor — distill the conversation into dense facts, prune the chatter.
            val compress by nodeLLMCompressHistory<Unit>("compression-interceptor", strategy = compression)

            // Switch to the deep-reasoning model for the heavy specification work.
            val useDesignModel by node<Unit, Unit>("use-design-model") {
                llm.writeSession { changeModel(technicalDesignModel) }
            }

            // Switch back to the fast model for the quick verification checks and HitL review.
            val useValidationModel by node<Unit, Unit>("use-validation-model") {
                llm.writeSession { changeModel(validationModel) }
            }

            // TechnicalDesign node — autonomous tool loop that writes the artifacts to disk.
            val technicalDesign by subgraphWithTask<Unit, Unit>(name = "technical-design") {
                """
                Using the extracted facts and approved requirements in this conversation, draft the
                following under $docsDir, using the write_file tool (absolute paths):
                  1. Architecture Decision Records as $docsDir/adr/NNNN-title.md (one per key decision).
                  2. An OpenAPI 3.1 description at $docsDir/openapi.yaml covering the API surface implied
                     by the epics.
                  3. A C4 model at $docsDir/c4/context.md and $docsDir/c4/container.md describing system
                     context and containers, with valid relative links between the documents.
                Keep every relative link pointing at a file you actually create. When done, finish.
                """.trimIndent()
            }

            // Validation node (Workflow 4) — lint the drafted files with local shell tools.
            val validate by subgraphWithTask<Unit, Unit>(name = "validation") {
                """
                Validate the documents you just drafted under $docsDir using run_shell_command in
                working directory $workspaceRoot:
                  - confirm every drafted file exists,
                  - check that markdown relative links resolve to files that exist,
                  - check that $docsDir/openapi.yaml parses as YAML (try `python3 -c 'import yaml,sys;
                    yaml.safe_load(open(sys.argv[1]))' $docsDir/openapi.yaml`, and fall back to a
                    structural grep if python/yaml is unavailable).
                Report any broken links or syntax errors you find, then finish.
                """.trimIndent()
            }

            val reportPrompt by node<Unit, String>("validation-report-prompt") {
                "Summarize the validation you just performed as a ValidationReport. List the absolute " +
                    "paths of every document you drafted, whether all checks passed, and the concrete findings."
            }
            val report by nodeLLMRequestStructured<ValidationReport>("validation-report")

            // Second HitL suspension — stream validation + paths, ask for Approve/Reject.
            val emitReview by node<ValidationReport, Unit>("architecture-review") { vr ->
                validationReport = vr
                phase = Phase.AWAITING_ARCH_REVIEW
                withAcpAgent {
                    sendEvent(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentMessageChunk(ContentBlock.Text(renderReview(vr)))
                        )
                    )
                }
            }

            nodeStart then ingestAnswers then compress then
                useDesignModel then technicalDesign then
                useValidationModel then validate then reportPrompt then report
            edge(report forwardTo emitReview transformed { it.getOrThrow().data })
            edge(emitReview forwardTo nodeFinish)
        }

        buildAgent(producer, config, strategy, executionToolRegistry(), streamDefaults = true).run(answers)
    }

    // =====================================================================================
    // Workflow 4 (part 2) — Final decision and teardown (git commit)
    // =====================================================================================
    private suspend fun runValidationAndTeardown(producer: ProducerScope<Event>, decision: String) {
        val config = AIAgentConfig(
            prompt = prompt("ba-teardown") {
                system(
                    """
                    You finalize an approved architecture by committing the drafted documents with git.
                    Workspace root: $workspaceRoot. Use run_shell_command for all git operations.
                    """.trimIndent()
                )
            },
            // Teardown is a deterministic git commit loop — keep it on the fast model.
            model = teardownModel,
            maxAgentIterations = 100,
        )

        val approvedFiles = validationReport?.files.orEmpty()

        val strategy = strategy<String, Unit>("ba-teardown") {
            val decide by node<String, Boolean>("decision") { text ->
                val t = text.lowercase()
                "approve" in t || t.trim() == "yes" || "lgtm" in t || "ship it" in t
            }

            // Teardown — stage and commit the approved files.
            val commit by subgraphWithTask<Unit, Unit>(name = "teardown-commit") {
                """
                The user approved the architecture. In working directory $workspaceRoot, use
                run_shell_command with git to stage and commit the drafted documents
                (${approvedFiles.joinToString(", ").ifBlank { "everything under $docsDir" }}).
                Use a conventional-commit message such as
                'docs(architecture): add ADRs, OpenAPI schema and C4 model'.
                Then report the resulting commit hash with `git rev-parse HEAD`. Finish when done.
                """.trimIndent()
            }

            val emitDone by node<Unit, Unit>("teardown-done") {
                phase = Phase.DONE
                withAcpAgent {
                    sendEvent(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentMessageChunk(
                                ContentBlock.Text("✅ Architecture approved and committed. The session can now be closed.")
                            )
                        )
                    )
                }
            }

            val emitRejected by node<Unit, Unit>("teardown-rejected") {
                // Allow the user to re-submit refined answers to regenerate the design.
                phase = Phase.AWAITING_REQUIREMENTS
                withAcpAgent {
                    sendEvent(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentMessageChunk(
                                ContentBlock.Text(
                                    "Understood — the architecture was not approved. Reply with refined " +
                                        "answers or additional constraints and I'll regenerate the design."
                                )
                            )
                        )
                    )
                }
            }

            nodeStart then decide
            edge(decide forwardTo commit onCondition { it } transformed { })
            edge(decide forwardTo emitRejected onCondition { !it } transformed { })
            edge(commit forwardTo emitDone)
            edge(emitDone forwardTo nodeFinish)
            edge(emitRejected forwardTo nodeFinish)
        }

        buildAgent(producer, config, strategy, executionToolRegistry(), streamDefaults = true).run(decision)
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================
    private fun buildAgent(
        producer: ProducerScope<Event>,
        config: AIAgentConfig,
        strategy: AIAgentGraphStrategy<String, Unit>,
        toolRegistry: ToolRegistry,
        streamDefaults: Boolean,
    ): AIAgent<String, Unit> = AIAgent(
        promptExecutor = promptExecutor,
        strategy = strategy,
        agentConfig = config,
        toolRegistry = toolRegistry,
        clock = clock,
    ) {
        install(AcpAgent) {
            this.sessionId = this@KoogAnalystSession.sessionId.value
            this.protocol = this@KoogAnalystSession.protocol
            this.eventsProducer = producer
            // During tool-heavy phases (design/validation/teardown) stream tool calls and thoughts
            // to the IDE automatically. During intake we emit only the curated form ourselves.
            this.setDefaultNotifications = streamDefaults
        }
    }

    /** Tools available during intake — read-only context gathering. */
    private fun intakeToolRegistry() = ToolRegistry {
        tool(::listDirectory.asTool())
        tool(::readFile.asTool())
    }

    /** Tools available during design, validation and teardown — read/write/shell. */
    private fun executionToolRegistry() = ToolRegistry {
        tool(::listDirectory.asTool())
        tool(::readFile.asTool())
        tool(::writeFile.asTool())
        tool(::editFile.asTool())
        tool(::runShellCommand.asTool())
    }

    private suspend fun emitMessage(producer: ProducerScope<Event>, message: String) {
        producer.send(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(message))))
    }
}

// ---- Plain-text renderers (the ACP form is rendered as structured markdown the IDE shows in chat) ----

private fun renderRequirementsForm(draft: RequirementsDraft): String = buildString {
    appendLine("## Drafted requirements")
    appendLine()
    appendLine(draft.summary)
    appendLine()
    appendLine("### Epics")
    draft.epics.forEachIndexed { i, epic ->
        appendLine("${i + 1}. **${epic.title}** — ${epic.description}")
        epic.acceptanceCriteria.forEach { appendLine("   - [ ] $it") }
    }
    if (draft.outOfScope.isNotEmpty()) {
        appendLine()
        appendLine("### Out of scope")
        draft.outOfScope.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("### ❓ Please answer to continue (HitL form)")
    draft.clarifyingQuestions.forEach { q ->
        appendLine("- **${q.id}** (${q.kind}): ${q.question}")
        if (q.options.isNotEmpty()) appendLine("    options: ${q.options.joinToString(" | ")}")
    }
    appendLine()
    appendLine("_Reply with your answers (e.g. `q1: ...`, `q2: ...`) to start technical design._")
}

private fun renderDraftText(draft: RequirementsDraft): String = buildString {
    appendLine("Summary: ${draft.summary}")
    appendLine("Epics:")
    draft.epics.forEach { epic ->
        appendLine("- ${epic.title}: ${epic.description}")
        epic.acceptanceCriteria.forEach { appendLine("    * AC: $it") }
    }
    if (draft.outOfScope.isNotEmpty()) appendLine("Out of scope: ${draft.outOfScope.joinToString("; ")}")
}

private fun renderQuestions(draft: RequirementsDraft): String =
    draft.clarifyingQuestions.joinToString("\n") { q ->
        "${q.id} (${q.kind}): ${q.question}" + if (q.options.isNotEmpty()) " [${q.options.joinToString(" | ")}]" else ""
    }

private fun renderReview(report: ValidationReport): String = buildString {
    appendLine(if (report.passed) "## ✅ Validation passed" else "## ⚠️ Validation found issues")
    appendLine()
    appendLine("### Drafted files")
    report.files.forEach { appendLine("- `$it`") }
    appendLine()
    appendLine("### Findings")
    report.findings.forEach { appendLine("- $it") }
    appendLine()
    appendLine("_Reply **approve** to commit these documents, or **reject** with feedback to regenerate._")
}

/**
 * Wires Koog sessions into the ACP agent lifecycle: advertises capabilities on initialize and mints
 * a fresh, isolated [KoogAnalystSession] per ACP session.
 */
class KoogAnalystSupport(
    private val promptExecutor: PromptExecutor,
    private val protocol: Protocol,
    private val clock: KoogClock,
) : AgentSupport {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        logger.info { "Initializing analyst agent for client: ${clientInfo.capabilities}" }
        return AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(
                loadSession = false,
                promptCapabilities = PromptCapabilities(
                    audio = false,
                    image = false,
                    embeddedContext = true,
                ),
            ),
            authMethods = emptyList(),
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val sessionId = SessionId(Uuid.random().toString())
        logger.info { "Creating analyst session $sessionId in ${sessionParameters.cwd}" }
        return KoogAnalystSession(
            sessionId = sessionId,
            promptExecutor = promptExecutor,
            protocol = protocol,
            clock = clock,
            workspaceRoot = sessionParameters.cwd,
        )
    }
}
