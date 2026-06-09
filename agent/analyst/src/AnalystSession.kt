package io.thisismo.vego.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.BraveModeConfirmationHandler
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.features.acp.AcpAgent
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.embeddings.base.Embedder
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.utils.time.KoogClock
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    embedder: Embedder,
    embeddingModelId: String,
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

    /**
     * The decision pool and the consensus strategy that synthesizes its verdicts. The strategy is
     * picked at launch via `ANALYST_CONSENSUS_STRATEGY` (default: weighted-matrix).
     */
    private val personaPool = PersonaPool(personaPoolConfig, promptExecutor, models.personaEvaluation)
    private val consensus: ConsensusStrategy = ConsensusStrategy.fromEnvironment()

    /** Semantic search over the committed-design index, backing `/facts <query>`. */
    private val semanticIndex = SemanticFactIndex(embedder, workspaceRoot, embeddingModelId)

    // ---- State that survives between turns while the session is suspended ----
    private var phase: Phase = Phase.INTAKE
    private var domainModel: DomainModel? = null
    private var conflictReport: ConflictReport? = null
    private var validationReport: ValidationReport? = null

    /** How many simulated-debate rounds the pool ran before settling — surfaced on the dashboard. */
    private var debateRounds: Int = 0

    /**
     * The active long-term-memory context. `null` is the default namespace; a value isolates hydration
     * and finalize under [memoryDirFor]. Seeded from `ANALYST_MEMORY_NAMESPACE` (switch contexts at
     * launch) and switchable mid-flight with the `/memory` command.
     */
    private var memoryNamespace: String? = System.getenv("ANALYST_MEMORY_NAMESPACE")?.trim()?.takeIf { it.isNotEmpty() }

    /** The workspace-relative memory directory for the active [memoryNamespace]. */
    private fun memoryDir(): String = memoryDirFor(memoryNamespace)

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

        // The single user-facing emit path for this turn: curated dashboards/headers plus the concise
        // tool-progress lines wired into each agent's EventHandler (see buildAgent).
        val reporter = ProgressReporter(this@channelFlow, workspaceRoot)

        // Control commands (/reset, /memory, /forget, /help) are handled here, ahead of the phase
        // machine, so they work from any phase without spinning up an agent.
        if (handleCommand(reporter, text)) return@channelFlow

        agentMutex.withLock {
            agentJob = async {
                // Retries are handled one layer down by the RetryingLLMClient (see resilientOpenAIExecutor).
                // This guard catches what survives them — an exhausted-retry API failure, a malformed
                // structured response, etc. — and ends the turn with a message instead of tearing down the
                // ACP flow. The phase only advances at each path's terminal emit node, so a mid-phase failure
                // leaves [phase] intact and the user can simply resend to retry the same step.
                try {
                    when (phase) {
                        Phase.INTAKE -> runIntake(reporter, text)
                        Phase.AWAITING_MODERATION -> runModerationAndDesign(reporter, text)
                        Phase.AWAITING_FINALIZE -> runFinalize(reporter, text)
                        Phase.DONE -> reporter.message("This analysis session is complete. Start a new session to analyze another idea.")
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    logger.error(e) { "Session $sessionId failed during phase $phase" }
                    reporter.message(
                        "${Ui.WARN} Something went wrong while ${phase.activity()} (${e.message ?: e::class.simpleName}). " +
                            "The retries built into the agent could not recover — this is usually a transient API or " +
                            "rate-limit issue. Resend your last message to retry this step.",
                    )
                }
            }
            agentJob?.await()
        }
    }

    /** A human-readable description of what a phase was doing, for failure messages. */
    private fun Phase.activity(): String = when (this) {
        Phase.INTAKE -> "modeling the domain and consulting the persona pool"
        Phase.AWAITING_MODERATION -> "reconciling consensus and drafting the specifications"
        Phase.AWAITING_FINALIZE -> "distilling the session into long-term memory"
        Phase.DONE -> "closing the session"
    }

    // =====================================================================================
    // Control commands — memory & context management (handled before the phase machine)
    // =====================================================================================

    /** Handle a leading-slash control command. Returns true (and ends the turn) when one was handled. */
    private suspend fun handleCommand(reporter: ProgressReporter, text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return false

        val split = trimmed.split(Regex("\\s+"), limit = 2)
        val cmd = split[0].lowercase()
        val arg = split.getOrNull(1)?.trim().orEmpty()

        when (cmd) {
            "/help" -> reporter.message(commandHelp())

            "/reset", "/new" -> {
                resetSession()
                reporter.message(
                    "🔄 **Session reset.** I cleared this conversation's working state — the domain " +
                        "model, the consensus standing, and any staged drafts — and I'm back at intake. " +
                        "Long-term memory is untouched. Send a new product idea to begin.",
                )
            }

            "/memory" -> when (arg.lowercase()) {
                "" -> reporter.message(memoryStatus())
                "list", "entries", "show" ->
                    reporter.message(renderMemoryEntries(memoryContextLabel(), listLongTermMemory(workspaceRoot, memoryDir())))
                "facts" -> reporter.message(renderPersistentFacts(listPersistentFacts(workspaceRoot)))
                "default", "reset" -> {
                    memoryNamespace = null
                    reporter.message("🧠 Switched to the **default** memory context (`$MEMORY_DIR_PATH`).")
                }
                else -> {
                    val ns = arg.replace(Regex("[^0-9A-Za-z._-]"), "-")
                    memoryNamespace = ns
                    reporter.message(
                        "🧠 Switched memory context to **$ns** (`${memoryDir()}`). New sessions hydrate " +
                            "from and finalize into this namespace; switch back with `/memory default`.",
                    )
                }
            }

            "/facts" -> handleFacts(reporter, arg)

            "/forget" -> {
                val (count, path) = archiveLongTermMemory(workspaceRoot, memoryDir(), clock.now().toString())
                if (count == 0) {
                    reporter.message("🗑️ No long-term memory to forget in `${memoryDir()}`.")
                } else {
                    reporter.message(
                        "🗑️ **Forgot $count memo(s)** from `${memoryDir()}`. They were archived " +
                            "(recoverable) to `$path`, so future sessions start without that history. The " +
                            "committed-design index is unaffected.",
                    )
                }
            }

            else -> reporter.message("Unknown command `$cmd`.\n\n${commandHelp()}")
        }

        // No agent runs for a command, so close the ACP turn ourselves.
        reporter.onAgentCompleted()
        return true
    }

    /** Clears the cross-turn working state and returns the session to intake. Memory is preserved. */
    private fun resetSession() {
        phase = Phase.INTAKE
        domainModel = null
        conflictReport = null
        validationReport = null
        committedSpecBaseline = null
        debateRounds = 0
    }

    /** Plain label for the active memory context — "default" or the namespace name. */
    private fun memoryContextLabel(): String = memoryNamespace ?: "default"

    private fun memoryStatus(): String {
        val ns = memoryNamespace?.let { "**$it**" } ?: "**default**"
        return "🧠 Active memory context: $ns (`${memoryDir()}`). Use `/memory list` to see stored " +
            "memos, `/facts` for the committed-design index, `/memory <name>` to switch, `/memory " +
            "default` to go back, or `/forget` to archive this context's memory."
    }

    private fun commandHelp(): String = buildString {
        appendLine("**Commands**")
        appendLine("- `/reset` (or `/new`) — start a fresh analysis; clears this conversation's state, keeps memory.")
        appendLine("- `/memory` — show the active long-term-memory context.")
        appendLine("- `/memory list` — visualize the memos stored in the active context.")
        appendLine("- `/memory <name>` — switch to a named context; `/memory default` to switch back.")
        appendLine("- `/facts` — list the persistent committed-design index (RAG facts).")
        appendLine("- `/facts <query>` — semantic search of that index, ranked by relevance.")
        appendLine("- `/forget` — archive (recoverably) the current context's long-term memory.")
        append("- `/help` — show this list.")
    }

    /** `/facts` with no argument lists the index; with a query it runs semantic search over it. */
    private suspend fun handleFacts(reporter: ProgressReporter, query: String) {
        val facts = listPersistentFacts(workspaceRoot)
        if (query.isBlank() || facts.isEmpty()) {
            val coverage = if (facts.isEmpty()) null else semanticIndex.coverage(facts)
            reporter.message(renderPersistentFacts(facts, coverage))
            return
        }
        reporter.step("🔎 Searching the committed-design index for “$query”…")
        val results = runCatching { semanticIndex.search(query, facts) }.getOrElse { e ->
            logger.error(e) { "Semantic /facts search failed" }
            reporter.message(
                "${Ui.WARN} Semantic search failed (${e.message ?: e::class.simpleName}); showing the " +
                    "full index instead.\n\n" + renderPersistentFacts(facts, null),
            )
            return
        }
        reporter.message(renderFactSearch(query, results))
    }

    override suspend fun cancel() {
        logger.info { "Cancelling analyst session $sessionId" }
        agentJob?.cancelAndJoin()
    }

    // =====================================================================================
    // Turn 1 — Nodes 1-4: Hydration + Ubiquitous Language, Domain Modeling, Pool, Consensus
    // =====================================================================================
    private suspend fun runIntake(reporter: ProgressReporter, idea: String) {
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
                reporter.step("${Ui.HYDRATE} Hydrating domain context — matching the most relevant prior designs…")
                // Semantic retrieval: pull only the committed designs closest to this idea into the
                // (expensive) domain-modeling prompt, rather than dumping the whole catalogue. Failures
                // degrade to the full-catalogue listing inside hydrateDomainContext, so intake never breaks.
                val relevant = runCatching {
                    semanticIndex.search(rawIdea, listPersistentFacts(workspaceRoot), topK = 6)
                }.getOrElse { e ->
                    logger.warn(e) { "Semantic hydration retrieval failed; falling back to the full catalogue." }
                    emptyList()
                }
                val context = hydrateDomainContext(
                    workspaceRoot,
                    memorySubPath = memoryDir(),
                    committedDesignSection = renderRelevantCommittedDesigns(relevant),
                )
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
                reporter.step("${Ui.MODEL} Domain model drafted — ${model.boundedContexts.size} bounded context(s). Consulting the pool…")
                model
            }

            // Nodes 3-4 — Persona Pool (fan-out) + simulated debate + Consensus Engine (fan-in).
            // The fan-out is a single node running the configured personas concurrently, because Koog's
            // `parallel()` needs statically-declared nodes whereas the pool size is config-driven.
            val poolConsensus by node<DomainModel, ConflictReport>("persona-pool-consensus") { model ->
                reporter.step("${Ui.POOL} ${personaPool.size} personas reviewing the model independently…")
                var verdicts = personaPool.evaluate(model)
                var report = consensus.synthesize(verdicts, model)
                var round = 0
                while (report.deadlocked && round < MAX_DEBATE_ROUNDS) {
                    round++
                    logger.info { "Pool deadlocked; running debate round $round/$MAX_DEBATE_ROUNDS." }
                    reporter.step("${Ui.DEBATE} Debate round $round/$MAX_DEBATE_ROUNDS — personas reconciling the disagreement…")
                    val debateContext = renderConflictReport(model, report)
                    verdicts = personaPool.evaluate(model, debateContext)
                    report = consensus.synthesize(verdicts, model)
                }
                debateRounds = round
                report
            }

            // Node 5 — HitL Pause 1: stream the Conflict Report dashboard and suspend the turn.
            val emitReport by node<ConflictReport, Unit>("conflict-report") { report ->
                conflictReport = report
                phase = Phase.AWAITING_MODERATION
                reporter.message(renderConflictReport(domainModel!!, report, debateRounds))
            }

            nodeStart then hydrate then modelDomain
            edge(modelDomain forwardTo storeModel transformed { it.getOrThrow().data })
            storeModel then poolConsensus then emitReport
            edge(emitReport forwardTo nodeFinish)
        }

        buildAgent(reporter, config, strategy, intakeToolRegistry()).run(idea)
    }

    // =====================================================================================
    // Turn 2 — Dynamic re-routing to consensus, then Nodes 6-8: Technical Design + Validation
    // =====================================================================================
    private suspend fun runModerationAndDesign(reporter: ProgressReporter, directive: String) {
        val model = domainModel
        val priorReport = conflictReport
        if (model == null || priorReport == null) {
            reporter.message("No domain model found for this session — please restart.")
            return
        }
        reporter.step("${Ui.DEBATE} Routing your directive through the pool, then drafting the specifications…")

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
                      - C4 diagrams as Mermaid under $c4Dir — Context and Container always, plus the
                        deeper Component/Dynamic/Deployment views when the architecture warrants them
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
                user("CONSENSUS STANDING BEFORE MODERATION:\n${renderConsensusFacts(model, priorReport)}")
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
                reporter.step("${Ui.DEBATE} Re-running consensus after your steer…")
                // The personas reconsider against the *full* dashboard (the rich debate context); only the
                // agent's own prompt is seeded with the terse facts digest, to keep its context window lean.
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
                        user("FINALIZED CONSENSUS (after moderation):\n${renderConsensusFacts(model, finalReport)}")
                    }
                }
            }

            // Compression Interceptor — distill the conversation into dense facts, prune the chatter.
            val compress by nodeLLMCompressHistory<Unit>("compression-interceptor", strategy = compression)

            // Switch to the deep-reasoning model for the heavy specification work.
            val useDesignModel by node<Unit, Unit>("use-design-model") {
                reporter.step("${Ui.DESIGN} Writing ADRs, C4 diagrams and UX specs into your working directory…")
                llm.writeSession { changeModel(models.technicalDesign) }
            }

            // Switch back to the fast model for the self-healing validation loop.
            val useValidationModel by node<Unit, Unit>("use-validation-model") {
                reporter.step("${Ui.VALIDATE} Validating and self-healing the drafted documents…")
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
                     Every bounded context from the agreed domain model must appear in the diagrams.
                     When the architecture warrants the extra depth — and only then — add deeper C4 views:
                     a C4Component diagram at $c4Dir/component.md (the components inside the most complex
                     container), a C4Dynamic diagram at $c4Dir/dynamic.md (the key cross-context runtime
                     interaction), and/or a C4Deployment diagram at $c4Dir/deployment.md (the deployment
                     topology). Skip any deeper view that would merely restate the container diagram.
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
                  2. If any reports issues (a broken relative link, a malformed/empty Mermaid diagram,
                     or a bounded context from the agreed domain model missing from the C4 diagrams),
                     fix the offending files with the edit-file or write-file tools, then re-call
                     lint_markdown_docs on that directory.
                  3. Repeat until all three directories report "OK — no issues found." (at most
                     $MAX_SELF_HEAL_ROUNDS repair rounds).
                Do NOT run git. Finish once the docs are clean (or you have exhausted the repair rounds).
                """.trimIndent()
            }

            // Authoritative report — recomputed deterministically in-process so it cannot be hallucinated.
            // Includes the domain-model cross-check, so a diagram set that dropped a bounded context
            // is reported even if the self-healing loop gave up on it.
            val buildReport by node<Unit, ValidationReport>("validation-report") {
                val (files, lintFindings) = validateMarkdownDocs(listOf(adrDir, c4Dir, uxDir))
                val findings = lintFindings + crossCheckC4AgainstDomainModel(c4Dir, model)
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
                reporter.message(renderReview(vr))
            }

            nodeStart then reconcile then compress then
                useDesignModel then technicalDesign then
                useValidationModel then selfHeal then buildReport then emitReview
            edge(emitReview forwardTo nodeFinish)
        }

        buildAgent(reporter, config, strategy, executionToolRegistry(model)).run(directive)
    }

    // =====================================================================================
    // Turn 3 — Node 9: Finalize confirmation, long-term memory, cache flush (NO git)
    // =====================================================================================
    private suspend fun runFinalize(reporter: ProgressReporter, decision: String) {
        if (!isAffirmative(decision)) {
            // Revise — route back to moderation so the user can re-steer the consensus and regenerate.
            phase = Phase.AWAITING_MODERATION
            reporter.message(
                "Understood — not finalizing yet. Reply with a refined directive or additional " +
                    "constraints and I'll re-route through the consensus engine, then reconcile the staged " +
                    "specifications against your steer — updating the drafts in place and removing any the " +
                    "revised decisions no longer need (committed docs from earlier sessions are left untouched).",
            )
            return
        }

        reporter.step("${Ui.MEMORY} Distilling the session into long-term memory…")
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
                conflictReport?.let { user("FINALIZED CONSENSUS:\n${renderConsensusFacts(domainModel!!, it)}") }
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
                    memorySubPath = memoryDir(),
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
                reporter.message(
                    "${Ui.DONE} Specification finalized. Session closed. The documents are in your " +
                        "working directory under `docs/adr`, `docs/c4`, and `docs/ux-specs` — review " +
                        "them in your IDE and run `git commit` whenever you're ready.\n\n" +
                        "Type `/reset` to start a new analysis, or `/help` for memory and context commands."
                )
            }

            nodeStart then distill
            edge(distill forwardTo persistAndFlush transformed { it.getOrThrow().data })
            persistAndFlush then emitDone then nodeFinish
        }

        // Finalize reads/distils only — no file or shell tools are exposed.
        buildAgent(reporter, config, strategy, ToolRegistry {}).run(decision)
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================
    private fun buildAgent(
        reporter: ProgressReporter,
        config: AIAgentConfig,
        strategy: AIAgentGraphStrategy<String, Unit>,
        toolRegistry: ToolRegistry,
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
            this.eventsProducer = reporter.producer
            // Off in every phase: Koog's default notifications dump raw tool args/results and stream the
            // model's prose. We render our own concise progress through the EventHandler below instead.
            this.setDefaultNotifications = false
        }
        // One terse line per tool call (writes/edits/lints/shell), plus the turn-completion stop reason
        // we'd otherwise lose by switching default notifications off. See [ProgressReporter].
        install(EventHandler) {
            onToolCallCompleted { reporter.onToolCompleted(it) }
            onToolCallFailed { reporter.onToolFailed(it) }
            onAgentCompleted { reporter.onAgentCompleted() }
            onAgentExecutionFailed { reporter.onAgentFailed(it) }
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
     * File and shell access are Koog's built-in tools; [LintTools] stays a custom tool set because it
     * is the domain-specific deterministic checker that backs the self-healing loop — carrying the
     * agreed [model] so the C4 diagrams are cross-checked against it. The shell tool runs in "brave
     * mode" (no interactive confirmation) — this agent talks ACP/JSON-RPC over stdio, so a console
     * confirmation prompt is impossible; the prompt forbids git and the user reviews and commits
     * everything manually.
     */
    private fun executionToolRegistry(model: DomainModel) = ToolRegistry {
        tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
        tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
        tool(WriteFileTool(JVMFileSystemProvider.ReadWrite))
        tool(EditFileTool(JVMFileSystemProvider.ReadWrite))
        tools(LintTools(c4Dir, model).asTools())
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
}
