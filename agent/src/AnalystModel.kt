package io.thisismo.vego.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Structured artifacts exchanged through the business-analysis / architecture state graph.
 *
 * These types are used both as structured LLM outputs (via `nodeLLMRequestStructured`) and as the
 * payloads that survive between ACP turns inside [KoogAnalystSession]. Keeping them serializable
 * lets us drop them straight into the prompt history when a suspended session is resumed.
 */

/** A single delivery-sized chunk of work with testable acceptance criteria. */
@Serializable
@LLMDescription("An epic: a coherent slice of product scope with concrete, testable acceptance criteria")
data class Epic(
    @property:LLMDescription("Short, action-oriented epic title")
    val title: String,
    @property:LLMDescription("One or two sentences describing the business value and scope of the epic")
    val description: String,
    @property:LLMDescription("Concrete, individually verifiable acceptance criteria for this epic")
    val acceptanceCriteria: List<String>,
)

/** The kind of UI control the IDE should render for a clarifying question. */
@Serializable
@LLMDescription("The input control the IDE should render for a clarifying question")
enum class QuestionKind {
    @LLMDescription("A free-form text answer")
    TEXT,

    @LLMDescription("A single choice out of the provided options (render as a dropdown or radio group)")
    SINGLE_CHOICE,

    @LLMDescription("Multiple choices out of the provided options (render as checkboxes)")
    MULTI_CHOICE,

    @LLMDescription("A yes/no answer (render as a toggle)")
    BOOLEAN,
}

/** A single clarifying question that must be answered before technical design can start. */
@Serializable
@LLMDescription("A clarifying question that blocks technical design until the user answers it")
data class ClarifyingQuestion(
    @property:LLMDescription("Stable identifier, e.g. q1, q2 — referenced when the user submits answers")
    val id: String,
    @property:LLMDescription("The question to ask the user")
    val question: String,
    @property:LLMDescription("Which input control the IDE should render for this question")
    val kind: QuestionKind,
    @property:LLMDescription("Options to choose from; only relevant for SINGLE_CHOICE / MULTI_CHOICE")
    val options: List<String> = emptyList(),
)

/**
 * The output of the BusinessAnalysis node: drafted requirements plus the questions that still need
 * answering. Workflow 1 stops here and hands [clarifyingQuestions] to Workflow 2 as the HitL form.
 */
@Serializable
@LLMDescription("Drafted requirements plus the clarifying questions needed before technical design")
data class RequirementsDraft(
    @property:LLMDescription("A concise restatement of the idea and the problem it solves")
    val summary: String,
    @property:LLMDescription("The epics decomposed from the idea, each with acceptance criteria")
    val epics: List<Epic>,
    @property:LLMDescription("Things explicitly out of scope, given the existing domain boundaries")
    val outOfScope: List<String>,
    @property:LLMDescription("Open questions that must be answered before architecture work begins")
    val clarifyingQuestions: List<ClarifyingQuestion>,
)

/**
 * The output of the Validation node: the outcome of running local linters/build tools over the
 * drafted architecture documents. Workflow 4 streams this to the IDE for the final Approve/Reject.
 */
@Serializable
@LLMDescription("The result of validating the drafted architecture documents with local tooling")
data class ValidationReport(
    @property:LLMDescription("True only if every drafted document passed validation")
    val passed: Boolean,
    @property:LLMDescription("Absolute paths of the documents that were drafted and validated")
    val files: List<String>,
    @property:LLMDescription("Human-readable findings: broken links, syntax errors, or 'all checks passed'")
    val findings: List<String>,
)
