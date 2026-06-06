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
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.common.Event
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
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * The lifecycle phase a session is in. Each ACP turn (`prompt`) runs one phase to completion and
 * then ends the turn — that turn boundary *is* the "native HitL suspension". While the IDE shows the
 * streamed form/result and waits for the user, no agent compute is running: the session simply holds
 * its [phase] and the artifacts drafted so far, and the next `prompt` resumes from there.
 */
private enum class Phase {
    /** Workflow 1: raw idea → Hydration → BusinessAnalysis → clarifying-questions form. */
    INTAKE,

    /** Suspended after the requirements form, waiting for the answered form. */
    AWAITING_REQUIREMENTS,

    /**
     * Workflows 1+2 have written validated specs straight into the working directory. Suspended
     * waiting for the user's Finalize / Revise confirmation (Workflow 3).
     */
    AWAITING_FINALIZE,

    /** Workflow 3 finalize done: memory distilled, caches flushed; ready for the user's manual commit. */
    DONE,
}

/**
 * Per-stage models. Each workflow stage is matched to a model whose strengths fit the task:
 *
 *  - [businessAnalysisModel] — fast, highly conversational; drives intake and the first HitL form.
 *  - [technicalDesignModel] — a reasoning model for the heavy specification work (ADRs / UX specs).
 *  - [validationModel] — fast again, for the self-healing validation loop.
 *  - [finalizeModel] — fast; distils the session into a durable long-term-memory memo.
 *
 * Within a single agent run the active model is switched per node via [llm.writeSession].changeModel,
 * which persists the new model on the shared LLM context for all subsequent nodes and subgraphs.
 */
private val businessAnalysisModel = OpenAIModels.Chat.GPT4o
private val technicalDesignModel = OpenAIModels.Chat.O3Mini
private val validationModel = OpenAIModels.Chat.GPT4o
private val finalizeModel = OpenAIModels.Chat.GPT4o

/** Bounds the self-healing repair loop so a doc the agent cannot fix can never spin forever. */
private const val MAX_SELF_HEAL_ROUNDS = 3

/**
 * A business-analyst / software-architect agent session driven over the Agent Client Protocol.
 *
 * It implements the rethought, human-centric Git workflows as a phase machine across ACP turns. The
 * agent has **no Git write access**: it prepares workspace changes for the user's manual review and
 * commit.
 *
 *  1. **Local workspace writing** — hydrate domain context, decompose the idea into epics +
 *     acceptance criteria, surface clarifying questions, then (after the answers) write ADRs and
 *     UI/UX specifications straight into the uncommitted working directory.
 *  2. **Pre-review self-healing validation** — a deterministic linter checks the drafted docs and
 *     the agent rewrites them until they are clean, *before* the user is alerted.
 *  3. **ACP finalize confirmation** — the validated specs are presented for a Finalize / Revise
 *     decision; on finalize the agent distils the choices into long-term memory, flushes its caches,
 *     and signals the user it is safe to commit manually. It never commits itself.
 *  4. *(out of process)* a post-commit hook re-indexes whatever the user actually committed.
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

    /** Where drafted specifications are written, straight into the user's working directory. */
    private val adrDir: String = "$workspaceRoot/docs/adr"
    private val uxDir: String = "$workspaceRoot/docs/ux-specs"

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
                    Phase.AWAITING_REQUIREMENTS -> runDesignAndSelfHeal(this@channelFlow, text)
                    Phase.AWAITING_FINALIZE -> runFinalize(this@channelFlow, text)
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
    // Workflow 1 (part 1) — Ingestion, Business Analysis, and the first HitL suspension
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
            // Hydration node — ground the analysis in retrieved domain context (memory + index + docs).
            val hydrate by node<String, String>("hydration") { rawIdea ->
                val context = hydrateDomainContext(workspaceRoot)
                """
                DOMAIN CONTEXT (retrieved from long-term memory, the committed-design index, and the workspace):
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
    // Workflow 1 (part 2) + Workflow 2 — Compression, spec writing, self-healing validation
    // =====================================================================================
    private suspend fun runDesignAndSelfHeal(producer: ProducerScope<Event>, answers: String) {
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
                    You are a pragmatic software architect and UX specifier. Given approved
                    requirements and the user's answers, you produce specification artifacts as files
                    on disk, writing them straight into the user's working directory (the user reviews
                    and commits them later — you must NEVER run git):
                      - Architecture Decision Records under $adrDir
                      - UI component inventories / UX specifications under $uxDir
                    You write valid Markdown with working relative links. You use the provided file and
                    lint tools; you never fabricate file contents you did not write. Workspace root: $workspaceRoot.
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

            // Switch back to the fast model for the self-healing validation loop.
            val useValidationModel by node<Unit, Unit>("use-validation-model") {
                llm.writeSession { changeModel(validationModel) }
            }

            // Workflow 1 — TechnicalDesign: autonomous tool loop that writes the artifacts to disk.
            val technicalDesign by subgraphWithTask<Unit, Unit>(name = "technical-design") {
                """
                Using the extracted facts and approved requirements in this conversation, write the
                following with the write_file tool (absolute paths), straight into the working directory:
                  1. Architecture Decision Records as $adrDir/NNNN-title.md — one per key decision,
                     each with Context / Decision / Consequences sections.
                  2. A UI component inventory at $uxDir/component-inventory.md listing each UI component
                     implied by the epics (name, purpose, key states, the epic it serves), plus any
                     per-screen UX specs as $uxDir/<screen>.md.
                Use working relative links between the documents, each pointing at a file you actually
                create. Do NOT run git. When every document is written, finish.
                """.trimIndent()
            }

            // Workflow 2 — Self-healing validation: lint with the deterministic tool, repair, repeat.
            val selfHeal by subgraphWithTask<Unit, Unit>(name = "self-healing-validation") {
                """
                Validate the documents you just wrote, BEFORE the user sees them:
                  1. Call lint_markdown_docs on $adrDir and again on $uxDir.
                  2. If either reports issues (e.g. a broken relative Markdown link), fix the offending
                     files with edit_file / write_file, then call lint_markdown_docs again.
                  3. Repeat until both directories report "OK — no issues found." (at most
                     $MAX_SELF_HEAL_ROUNDS repair rounds).
                Do NOT run git. Finish once the docs are clean (or you have exhausted the repair rounds).
                """.trimIndent()
            }

            // Authoritative report — recomputed deterministically in-process so it cannot be hallucinated.
            val buildReport by node<Unit, ValidationReport>("validation-report") {
                val (files, findings) = validateMarkdownDocs(listOf(adrDir, uxDir))
                ValidationReport(
                    passed = findings.isEmpty(),
                    files = files.sorted(),
                    findings = if (findings.isEmpty()) listOf("All drafted documents passed validation.")
                    else findings.map { "${it.file}: ${it.message}" },
                )
            }

            // HitL suspension — stream the validated specs and ask for Finalize / Revise.
            val emitReview by node<ValidationReport, Unit>("finalize-prompt") { vr ->
                validationReport = vr
                phase = Phase.AWAITING_FINALIZE
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
                useValidationModel then selfHeal then buildReport then emitReview
            edge(emitReview forwardTo nodeFinish)
        }

        buildAgent(producer, config, strategy, executionToolRegistry(), streamDefaults = true).run(answers)
    }

    // =====================================================================================
    // Workflow 3 — Finalize confirmation, long-term memory, cache flush (NO git)
    // =====================================================================================
    private suspend fun runFinalize(producer: ProducerScope<Event>, decision: String) {
        if (!isAffirmative(decision)) {
            // Revise — let the user re-submit refined answers to regenerate the specs.
            phase = Phase.AWAITING_REQUIREMENTS
            emitMessage(
                producer,
                "Understood — not finalizing yet. Reply with refined answers or additional " +
                    "constraints and I'll regenerate the specifications.",
            )
            return
        }

        val report = validationReport
        val docExcerpts = readDocExcerpts(report?.files.orEmpty())

        val config = AIAgentConfig(
            prompt = prompt("ba-finalize") {
                system(
                    """
                    You distil a finished specification session into ONE dense ArchitectureMemo for the
                    agent's long-term memory: the key decisions, the hard constraints/non-goals, the UI
                    components established, and the document paths produced. Be terse and factual; this
                    memo is read by future sessions, not by the user. You do not write files or run git.
                    """.trimIndent()
                )
                requirementsDraft?.let { user("APPROVED REQUIREMENTS:\n${renderDraftText(it)}") }
                user("VALIDATED SPECIFICATION DOCUMENTS:\n$docExcerpts")
            },
            model = finalizeModel,
            maxAgentIterations = 20,
        )

        val strategy = strategy<String, Unit>("ba-finalize") {
            // 1. Distil the architectural choices into a structured memo.
            val distill by nodeLLMRequestStructured<ArchitectureMemo>("distill-memory")

            // 2. Persist the memo to long-term memory, then flush in-memory caches to free RAM.
            val persistAndFlush by node<ArchitectureMemo, Unit>("persist-and-flush") { memo ->
                val path = persistArchitectureMemory(
                    workspaceRoot = workspaceRoot,
                    sessionId = sessionId.value,
                    isoTimestamp = clock.now().toString(),
                    markdown = renderMemo(memo),
                )
                logger.info { "Persisted architecture memory to $path" }
                // Flush active context caches — drop the large per-turn artifacts now they are durable.
                requirementsDraft = null
                validationReport = null
                phase = Phase.DONE
            }

            // 3. Print the closure message.
            val emitDone by node<Unit, Unit>("finalize-done") {
                withAcpAgent {
                    sendEvent(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentMessageChunk(
                                ContentBlock.Text(
                                    "✅ Specification finalized. Session closed. The documents are in your " +
                                        "working directory under `docs/adr` and `docs/ux-specs` — review them in " +
                                        "your IDE and run `git commit` whenever you're ready."
                                )
                            )
                        )
                    )
                }
            }

            nodeStart then distill
            edge(distill forwardTo persistAndFlush transformed { it.getOrThrow().data })
            persistAndFlush then emitDone then nodeFinish
        }

        // Finalize reads/distils only — no file or shell tools are exposed.
        buildAgent(producer, config, strategy, ToolRegistry {}, streamDefaults = false).run(decision)
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
            // During tool-heavy phases (design/validation) stream tool calls and thoughts to the IDE
            // automatically. During intake/finalize we emit only the curated message ourselves.
            this.setDefaultNotifications = streamDefaults
        }
    }

    /** Tools available during intake — read-only context gathering. */
    private fun intakeToolRegistry() = ToolRegistry {
        tool(::listDirectory.asTool())
        tool(::readFile.asTool())
    }

    /** Tools available during design + self-healing validation — read/write/lint/shell (never git). */
    private fun executionToolRegistry() = ToolRegistry {
        tool(::listDirectory.asTool())
        tool(::readFile.asTool())
        tool(::writeFile.asTool())
        tool(::editFile.asTool())
        tool(::lintMarkdownDocs.asTool())
        tool(::runShellCommand.asTool())
    }

    private fun readDocExcerpts(files: List<String>, maxChars: Int = 1_500): String {
        if (files.isEmpty()) return "(no documents were recorded for this session)"
        return files.joinToString("\n\n") { abs ->
            val rel = abs.removePrefix("$workspaceRoot/")
            val body = runCatching { Path(abs).readText().take(maxChars) }.getOrElse { "(could not read)" }
            "--- $rel ---\n$body"
        }
    }

    private suspend fun emitMessage(producer: ProducerScope<Event>, message: String) {
        producer.send(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(message))))
    }
}
