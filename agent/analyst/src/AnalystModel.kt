package io.thisismo.vego.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Structured artifacts exchanged through the business-analysis / architecture state graph.
 *
 * These types are used both as structured LLM outputs (via `nodeLLMRequestStructured`) and as the
 * payloads that survive between ACP turns inside [KoogAnalystSession]. Keeping them serializable
 * lets us drop them straight into the prompt history when a suspended session is resumed.
 *
 * The domain model produced by Nodes 1–2 lives in [DomainModel]; the pool evaluation and consensus
 * artifacts live in [PersonaEvaluation] / [ConflictReport]. This file holds the remaining shared
 * artifacts: the deterministic validation report and the durable architecture memo.
 */

/**
 * The output of the Validation node: the outcome of running local linters over the drafted
 * specification documents. In the rethought workflow the agent **self-heals** against this report
 * (rewriting files until it is clean) *before* streaming it to the IDE for the final Finalize/Revise
 * decision — so by the time the user sees it, `passed` should normally be true.
 */
@Serializable
@LLMDescription("The result of validating the drafted specification documents with local tooling")
data class ValidationReport(
    @property:LLMDescription("True only if every drafted document passed validation")
    val passed: Boolean,
    @property:LLMDescription("Absolute paths of the documents that were drafted and validated")
    val files: List<String>,
    @property:LLMDescription("Human-readable findings: broken links, syntax errors, or 'all checks passed'")
    val findings: List<String>,
)

/**
 * The distilled, durable record of an analysis session, written to the agent's long-term memory on
 * finalize (Workflow 3). It is intentionally small and dense: the next session's hydration step
 * reads these memos so the agent "remembers" the architecture it has already established without
 * re-reading every drafted document. This is the in-process counterpart to the committed-doc RAG
 * index that the post-commit hook (Workflow 4) maintains.
 */
@Serializable
@LLMDescription("A dense, durable summary of the architectural choices made in this session")
data class ArchitectureMemo(
    @property:LLMDescription("A short, descriptive title for what was specified in this session")
    val title: String,
    @property:LLMDescription("The key architecture decisions that were made (one terse line each)")
    val decisions: List<String>,
    @property:LLMDescription("Hard constraints, non-goals, and deprecations that future work must respect")
    val constraints: List<String>,
    @property:LLMDescription("The UI components / modules / containers that this session established")
    val components: List<String>,
    @property:LLMDescription("Relative paths (from the workspace root) of the specification documents produced")
    val documents: List<String>,
)
