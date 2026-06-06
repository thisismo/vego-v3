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
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.BraveModeConfirmationHandler
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.features.acp.AcpAgent
import ai.koog.agents.features.acp.withAcpAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.utils.time.KoogClock
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
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
 * streamed dashboard/result and waits for the user, no agent compute is running: the session simply
 * holds its [phase] and the artifacts drafted so far, and the next `prompt` resumes from there.
 */
private enum class Phase {
    /** Turn 1: raw idea → Hydration + Ubiquitous Language → Domain Modeling → Persona Pool → Consensus. */
    INTAKE,

    /** Suspended after HitL Pause 1 (the Conflict Report), waiting for the human's moderation directive. */
    AWAITING_MODERATION,

    /**
     * Turn 2 has re-routed through consensus and written validated specs (ADRs + C4 + UX) into the
     * working directory. Suspended at HitL Pause 2, waiting for Finalize / Revise.
     */
    AWAITING_FINALIZE,

    /** Finalize done: memory distilled, caches flushed; ready for the user's manual commit. */
    DONE,
}

/** Bounds the self-healing repair loop so a doc the agent cannot fix can never spin forever. */
private const val MAX_SELF_HEAL_ROUNDS = 3

/** Bounds the simulated-debate loop so a pool that cannot converge falls through to human moderation. */
private const val MAX_DEBATE_ROUNDS = 2

/**
 * A consensus-driven business-analyst / software-architect agent session over the Agent Client Protocol.
 *
 * It implements the DDD + democratic-consensus lifecycle as a phase machine across ACP turns. The
 * agent has **no Git write access**: it prepares workspace changes for the user's manual review and
 * commit.
 *
 *  1. **Domain inception & modeling** — hydrate domain context, extract the ubiquitous language, and
 *     draft bounded contexts + aggregate roots into a [DomainModel].
 *  2. **Bounded-context evaluation (the pool)** — broadcast the model to the configured persona pool;
 *     each persona evaluates it independently and concurrently.
 *  3. **Simulated debate + consensus** — a bounded debate loop lets personas revise in light of the
 *     disagreement; a pluggable [ConsensusStrategy] then synthesizes a deterministic [ConflictReport].
 *  4. **Human moderation (HitL Pause 1)** — the IDE shows the confidence matrix; the human injects a
 *     directive that routes back through the consensus engine.
 *  5. **Technical design & validation** — ADRs and C4 (Mermaid) diagrams are written to disk and
 *     self-healed against a deterministic linter before HitL Pause 2.
 *  6. **Finalize** — the choices are distilled into long-term memory; the user commits manually.
 */
class KoogAnalystSession(
    override val sessionId: SessionId,
    private val promptExecutor: PromptExecutor,
    private val protocol: Protocol,
    private val clock: KoogClock,
    private val workspaceRoot: String,
    private val models: AnalystModelConfig,
    personaPoolConfig: PersonaPoolConfig,
) : AgentSession {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /** Where drafted specifications are written, straight into the user's working directory. */
    private val adrDir: String = "$workspaceRoot/docs/adr"
    private val c4Dir: String = "$workspaceRoot/docs/c4"
    private val uxDir: String = "$workspaceRoot/docs/ux-specs"

    /** The decision pool and the consensus strategy that synthesizes its verdicts. */
    private val personaPool = PersonaPool(personaPoolConfig, promptExecutor, models.personaEvaluation)
    private val consensus: ConsensusStrategy = WeightedMatrixConsensus()

    // ---- State that survives between turns while the session is suspended ----
    private var phase: Phase = Phase.INTAKE
    private var domainModel: DomainModel? = null
    private var conflictReport: ConflictReport? = null
    private var validationReport: ValidationReport? = null

    /**
     * The spec docs already on disk when this session first entered design — the *committed* baseline
     * from earlier sessions. Captured once (lazily) so the design/revise loop can tell those docs
     * (preserve, extend) apart from the drafts this session staged (reconcile, delete on a rejected
     * round). Null until the first design round runs.
     */
    private var committedSpecBaseline: Set<String>? = null

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
                // Retries are handled one layer down by the RetryingLLMClient (see resilientOpenAIExecutor).
                // This guard catches what survives them — an exhausted-retry API failure, a malformed
                // structured response, etc. — and ends the turn with a message instead of tearing down the
                // ACP flow. The phase only advances at each path's terminal emit node, so a mid-phase failure
                // leaves [phase] intact and the user can simply resend to retry the same step.
                try {
                    when (phase) {
                        Phase.INTAKE -> runIntake(this@channelFlow, text)
                        Phase.AWAITING_MODERATION -> runModerationAndDesign(this@channelFlow, text)
                        Phase.AWAITING_FINALIZE -> runFinalize(this@channelFlow, text)
                        Phase.DONE -> emitMessage(this@channelFlow, "This analysis session is complete. Start a new session to analyze another idea.")
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    logger.error(e) { "Session $sessionId failed during phase $phase" }
                    emitMessage(
                        this@channelFlow,
                        "⚠️ Something went wrong while processing this step (${e.message ?: e::class.simpleName}). " +
                            "The retries built into the agent could not recover — this is usually a transient API or " +
                            "rate-limit issue. Resend your last message to retry this step.",
                    )
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
    // Turn 1 — Nodes 1-4: Hydration + Ubiquitous Language, Domain Modeling, Pool, Consensus
    // =====================================================================================
    private suspend fun runIntake(producer: ProducerScope<Event>, idea: String) {
        val config = AIAgentConfig(
            prompt = prompt("ddd-modeling") {
                system(
                    """
                    You are a senior domain modeler practising Domain-Driven Design. You turn raw product
                    ideas into a formal domain model. You evaluate the idea against the existing domain
                    boundaries in the provided project context, extract a ubiquitous-language dictionary,
                    and decompose the domain into bounded contexts with their aggregate roots, entities,
                    and invariants. You mark what is explicitly out of scope. Never invent scope the user
                    did not imply, and prefer fewer, sharper bounded contexts over many vague ones.
                    """.trimIndent()
                )
            },
            model = models.domainModeling,
            maxAgentIterations = 50,
        )

        val strategy = strategy<String, Unit>("ddd-modeling") {
            // Node 1 — Context Hydration: ground the model in retrieved domain context (memory + index + docs).
            val hydrate by node<String, String>("hydration") { rawIdea ->
                val context = hydrateDomainContext(workspaceRoot)
                """
                DOMAIN CONTEXT (retrieved from long-term memory, the committed-design index, and the workspace):
                $context

                RAW IDEA SUBMITTED BY THE USER:
                $rawIdea

                Produce a DomainModel: a short summary, the ubiquitous-language dictionary, the bounded
                contexts (each with its aggregate roots, their entities and invariants, and integration
                points), and the out-of-scope items.
                """.trimIndent()
            }

            // Node 2 — Domain Modeling: structured DDD model.
            val modelDomain by nodeLLMRequestStructured<DomainModel>("domain-modeling")

            // Persist the drafted model for the pool and for the suspended turns.
            val storeModel by node<DomainModel, DomainModel>("store-model") { model ->
                domainModel = model
                model
            }

            // Nodes 3-4 — Persona Pool (fan-out) + simulated debate + Consensus Engine (fan-in).
            // The fan-out is a single node running the configured personas concurrently, because Koog's
            // `parallel()` needs statically-declared nodes whereas the pool size is config-driven.
            val poolConsensus by node<DomainModel, ConflictReport>("persona-pool-consensus") { model ->
                var verdicts = personaPool.evaluate(model)
                var report = consensus.synthesize(verdicts, model)
                var round = 0
                while (report.deadlocked && round < MAX_DEBATE_ROUNDS) {
                    round++
                    logger.info { "Pool deadlocked; running debate round $round/$MAX_DEBATE_ROUNDS." }
                    val debateContext = renderConflictReport(model, report)
                    verdicts = personaPool.evaluate(model, debateContext)
                    report = consensus.synthesize(verdicts, model)
                }
                report
            }

            // Node 5 — HitL Pause 1: stream the Conflict Report dashboard and suspend the turn.
            val emitReport by node<ConflictReport, Unit>("conflict-report") { report ->
                conflictReport = report
                phase = Phase.AWAITING_MODERATION
                withAcpAgent {
                    sendEvent(
                        Event.SessionUpdateEvent(
                            SessionUpdate.AgentMessageChunk(ContentBlock.Text(renderConflictReport(domainModel!!, report)))
                        )
                    )
                }
            }

            nodeStart then hydrate then modelDomain
            edge(modelDomain forwardTo storeModel transformed { it.getOrThrow().data })
            storeModel then poolConsensus then emitReport
            edge(emitReport forwardTo nodeFinish)
        }

        buildAgent(producer, config, strategy, intakeToolRegistry(), streamDefaults = false).run(idea)
    }

    // =====================================================================================
    // Turn 2 — Dynamic re-routing to consensus, then Nodes 6-8: Technical Design + Validation
    // =====================================================================================
    private suspend fun runModerationAndDesign(producer: ProducerScope<Event>, directive: String) {
        val model = domainModel
        val priorReport = conflictReport
        if (model == null || priorReport == null) {
            emitMessage(producer, "No domain model found for this session — please restart.")
            return
        }

        // Reconcile against what is already on disk. The first time we design, capture the committed
        // baseline (docs from earlier sessions — preserve them). On a revise round the baseline is
        // already set, so everything beyond it is *this session's* draft, which the agent must update
        // in place or delete rather than leave behind as an orphan.
        val specDirs = listOf(adrDir, c4Dir, uxDir)
        val isRevise = committedSpecBaseline != null
        val baseline = committedSpecBaseline ?: specDocPaths(specDirs).toSet().also { committedSpecBaseline = it }
        val reconciliationGuidance = buildReconciliationGuidance(specDirs, baseline, isRevise)

        val config = AIAgentConfig(
            // Seed the prompt with the agreed model and the consensus standing; the compression
            // interceptor distils this into facts before the heavy design work begins.
            prompt = prompt("ba-design") {
                system(
                    """
                    You are a pragmatic software architect and UX specifier. Given an agreed domain model,
                    the consensus standing of the persona pool, and the human's moderation directive, you
                    produce specification artifacts as files on disk, written straight into the user's
                    working directory (the user reviews and commits them later — you must NEVER run git):
                      - Architecture Decision Records under $adrDir
                      - C4 Context and Container diagrams (as Mermaid) under $c4Dir
                      - UI component inventories / UX specifications under $uxDir
                    You write valid Markdown with working relative links and valid Mermaid. You use the
                    provided file, lint and delete tools; you never fabricate file contents. When drafts
                    from an earlier, rejected round are present you reconcile them — updating them in place
                    or deleting the ones the revised decisions no longer need (delete_spec_doc), never
                    leaving orphans — while preserving specs committed in previous sessions. Workspace
                    root: $workspaceRoot.
                    """.trimIndent()
                )
                user("AGREED DOMAIN MODEL:\n${renderDomainModel(model)}")
                user("CONSENSUS STANDING BEFORE MODERATION:\n${renderConflictReport(model, priorReport)}")
            },
            // Base model for the cheap framing nodes; the graph switches to the reasoning model for
            // design and back to the fast model for the self-healing validation loop.
            model = models.validation,
            maxAgentIterations = 200,
        )

        // The concepts the compression interceptor extracts rigid "Facts" about.
        val compression = FactRetrievalHistoryCompressionStrategy(
            Concept("decisions", "Architecture decisions agreed for the bounded contexts and aggregate roots", FactType.MULTIPLE),
            Concept("constraints", "Technical constraints, non-goals, and the human moderation directive", FactType.MULTIPLE),
            Concept("domain", "The agreed bounded contexts, aggregates, and their integration points", FactType.MULTIPLE),
        )

        val strategy = strategy<String, Unit>("ba-design") {
            // Dynamic re-routing — route the human directive back through the consensus engine to
            // finalize the agreement, then inject the result into the conversation for the design work.
            val reconcile by node<String, Unit>("reconcile-consensus") { humanDirective ->
                val debateContext = buildString {
                    appendLine("HUMAN MODERATION DIRECTIVE:")
                    appendLine(humanDirective)
                    appendLine()
                    appendLine("PRIOR CONSENSUS STANDING:")
                    append(renderConflictReport(model, priorReport))
                }
                val verdicts = personaPool.evaluate(model, debateContext)
                val finalReport = consensus.synthesize(verdicts, model)
                conflictReport = finalReport
                logger.info { "Re-routed through consensus after moderation; deadlocked=${finalReport.deadlocked}." }
                llm.writeSession {
                    appendPrompt {
                        user("HUMAN MODERATION DIRECTIVE:\n$humanDirective")
                        user("FINALIZED CONSENSUS (after moderation):\n${renderConflictReport(model, finalReport)}")
                    }
                }
            }

            // Compression Interceptor — distill the conversation into dense facts, prune the chatter.
            val compress by nodeLLMCompressHistory<Unit>("compression-interceptor", strategy = compression)

            // Switch to the deep-reasoning model for the heavy specification work.
            val useDesignModel by node<Unit, Unit>("use-design-model") {
                llm.writeSession { changeModel(models.technicalDesign) }
            }

            // Switch back to the fast model for the self-healing validation loop.
            val useValidationModel by node<Unit, Unit>("use-validation-model") {
                llm.writeSession { changeModel(models.validation) }
            }

            // Node 6 — Technical Design: autonomous tool loop that writes ADRs + C4 diagrams + UX to disk.
            val technicalDesign by subgraphWithTask<Unit, Unit>(name = "technical-design") {
                """
                Using the agreed domain model, the finalized consensus, and the extracted facts in this
                conversation, write the following with the write-file tool (absolute paths), straight into
                the working directory:
                  1. Architecture Decision Records as $adrDir/NNNN-title.md — one per key decision, each
                     with Context / Decision / Consequences sections.
                  2. A C4 Context diagram at $c4Dir/context.md and a C4 Container diagram at
                     $c4Dir/container.md, each as a Mermaid ```mermaid``` block (use C4Context / C4Container,
                     or flowchart if clearer) reflecting the bounded contexts and their integration points.
                  3. A UI component inventory at $uxDir/component-inventory.md listing each UI component
                     implied by the model (name, purpose, key states, the context it serves), plus any
                     per-screen UX specs as $uxDir/<screen>.md.
                Use working relative links between the documents, each pointing at a file you actually
                create. Do NOT run git. When every document is written, finish.
                """.trimIndent() + reconciliationGuidance
            }

            // Node 7 — Self-healing validation: lint links + Mermaid with the deterministic tool, repair, repeat.
            val selfHeal by subgraphWithTask<Unit, Unit>(name = "self-healing-validation") {
                """
                Validate the documents you just wrote, BEFORE the user sees them:
                  1. Call lint_markdown_docs on $adrDir, then $c4Dir, then $uxDir.
                  2. If any reports issues (a broken relative link, or a malformed/empty Mermaid diagram),
                     fix the offending files with the edit-file or write-file tools, then re-call
                     lint_markdown_docs on that directory.
                  3. Repeat until all three directories report "OK — no issues found." (at most
                     $MAX_SELF_HEAL_ROUNDS repair rounds).
                Do NOT run git. Finish once the docs are clean (or you have exhausted the repair rounds).
                """.trimIndent()
            }

            // Authoritative report — recomputed deterministically in-process so it cannot be hallucinated.
            val buildReport by node<Unit, ValidationReport>("validation-report") {
                val (files, findings) = validateMarkdownDocs(listOf(adrDir, c4Dir, uxDir))
                ValidationReport(
                    passed = findings.isEmpty(),
                    files = files.sorted(),
                    findings = if (findings.isEmpty()) listOf("All drafted documents passed validation.")
                    else findings.map { "${it.file}: ${it.message}" },
                )
            }

            // Node 8 — HitL Pause 2: stream the validated specs and ask for Finalize / Revise.
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

            nodeStart then reconcile then compress then
                useDesignModel then technicalDesign then
                useValidationModel then selfHeal then buildReport then emitReview
            edge(emitReview forwardTo nodeFinish)
        }

        buildAgent(producer, config, strategy, executionToolRegistry(), streamDefaults = true).run(directive)
    }

    // =====================================================================================
    // Turn 3 — Node 9: Finalize confirmation, long-term memory, cache flush (NO git)
    // =====================================================================================
    private suspend fun runFinalize(producer: ProducerScope<Event>, decision: String) {
        if (!isAffirmative(decision)) {
            // Revise — route back to moderation so the user can re-steer the consensus and regenerate.
            phase = Phase.AWAITING_MODERATION
            emitMessage(
                producer,
                "Understood — not finalizing yet. Reply with a refined directive or additional " +
                    "constraints and I'll re-route through the consensus engine, then reconcile the staged " +
                    "specifications against your steer — updating the drafts in place and removing any the " +
                    "revised decisions no longer need (committed docs from earlier sessions are left untouched).",
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
                    components and bounded contexts established, and the document paths produced. Be terse
                    and factual; this memo is read by future sessions, not by the user. You do not write
                    files or run git.
                    """.trimIndent()
                )
                domainModel?.let { user("AGREED DOMAIN MODEL:\n${renderDomainModel(it)}") }
                conflictReport?.let { user("FINALIZED CONSENSUS:\n${renderConflictReport(domainModel!!, it)}") }
                user("VALIDATED SPECIFICATION DOCUMENTS:\n$docExcerpts")
            },
            model = models.finalize,
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
                domainModel = null
                conflictReport = null
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
                                        "working directory under `docs/adr`, `docs/c4`, and `docs/ux-specs` — review " +
                                        "them in your IDE and run `git commit` whenever you're ready."
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

    /** Tools available during intake — read-only context gathering (Koog built-ins). */
    private fun intakeToolRegistry() = ToolRegistry {
        tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
        tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
    }

    /**
     * Tools available during design + self-healing validation — read/write/lint/shell (never git).
     *
     * File and shell access are Koog's built-in tools; [lintMarkdownDocs] stays a custom tool because
     * it is the domain-specific deterministic checker that backs the self-healing loop. The shell tool
     * runs in "brave mode" (no interactive confirmation) — this agent talks ACP/JSON-RPC over stdio,
     * so a console confirmation prompt is impossible; the prompt forbids git and the user reviews and
     * commits everything manually.
     */
    private fun executionToolRegistry() = ToolRegistry {
        tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
        tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
        tool(WriteFileTool(JVMFileSystemProvider.ReadWrite))
        tool(EditFileTool(JVMFileSystemProvider.ReadWrite))
        tool(::lintMarkdownDocs.asTool())
        // Deletion is not a Koog built-in; this hard-scopes it to the spec dirs so a rejected round
        // can retire its stale drafts without ever reaching the rest of the workspace.
        tools(SpecDocTools(listOf(adrDir, c4Dir, uxDir)).asTools())
        tool(ExecuteShellCommandTool(JvmShellCommandExecutor(), BraveModeConfirmationHandler()))
    }

    /**
     * The reconciliation instructions appended to the design task, so a design round writes against
     * what is already on disk instead of blindly appending. [baseline] is the committed-docs snapshot
     * captured before this session's first design round; [isRevise] is true on every re-design after a
     * rejected finalize. Returns "" when there is nothing to reconcile (a fresh project's first round).
     */
    private fun buildReconciliationGuidance(specDirs: List<String>, baseline: Set<String>, isRevise: Boolean): String {
        val current = specDocPaths(specDirs)
        val committedInventory = renderSpecDocInventory(workspaceRoot, current.filter { it in baseline })
        val stagedInventory = renderSpecDocInventory(workspaceRoot, current.filter { it !in baseline })

        if (!isRevise) {
            // First design round: only the committed baseline (if any) exists. Preserve and extend it.
            committedInventory ?: return ""
            return "\n\n" + """
                EXISTING COMMITTED SPECS — these were committed in earlier sessions. Treat them as the
                established record: do NOT delete or rewrite them wholesale. Continue ADR numbering after
                the highest existing NNNN, and only edit one in place (edit-file) if a new decision
                genuinely supersedes it (note the supersession in both documents).
            """.trimIndent() + "\n" + committedInventory
        }

        // Revise round: the docs beyond the committed baseline are this session's previously REJECTED
        // drafts. Reconcile them against the new directive — update in place or delete; never orphan.
        if (stagedInventory == null) {
            return "\n\n" + """
                NOTE: a previous round's drafts were already cleared. Write the revised specifications
                from scratch per the instructions above.
            """.trimIndent()
        }
        val committedNote = committedInventory?.let {
            "\n\nThese committed specs predate this session — preserve them, do not delete:\n$it"
        }.orEmpty()
        return "\n\n" + """
            REVISE ROUND — reconcile the rejected drafts. The documents below were written in a previous
            round that the user REJECTED. Given the new moderation directive, make the staged set reflect
            ONLY the current decisions:
              - Update a draft in place (edit-file or write-file) where it still applies.
              - DELETE (delete_spec_doc) any draft the revised decisions no longer need — do not leave it
                behind and do not recreate the same decision under a different filename.
        """.trimIndent() + "\n" + stagedInventory + committedNote
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
