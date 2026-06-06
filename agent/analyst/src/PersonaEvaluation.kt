package io.thisismo.vego.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The persona pool's evaluation artifacts and the fan-out runner that drives them.
 *
 * Koog's `parallel()` graph construct needs a statically-declared set of nodes, so a *runtime-sized*
 * pool cannot be modeled as separate graph nodes. Instead the pool is a single graph node whose body
 * fans out with coroutines: each persona runs an **independent** [PromptExecutor.executeStructured]
 * call (its own prompt, its own temperature) concurrently, and the node joins the results. Going
 * through the raw executor — rather than the shared agent LLM session — keeps the evaluations truly
 * isolated, which is exactly the "independent analysis" the design calls for.
 */

/** A persona's overall stance on the drafted domain model. */
@Serializable
@LLMDescription("A persona's overall stance on the drafted domain model")
enum class Verdict {
    @LLMDescription("No blocking concerns; the model is acceptable as drafted")
    APPROVE,

    @LLMDescription("Acceptable, but with concerns that should be recorded and ideally addressed")
    APPROVE_WITH_CONCERNS,

    @LLMDescription("A hard blocker: the model must change before it can advance")
    BLOCK,
}

/** One persona's confidence in a single bounded context, with the reasoning behind the score. */
@Serializable
@LLMDescription("A persona's confidence in one bounded context")
data class ContextAssessment(
    @property:LLMDescription("The exact name of the bounded context being assessed")
    val boundedContext: String,
    @property:LLMDescription("Confidence from 0 (unacceptable) to 100 (fully endorsed) for this context")
    val confidence: Int,
    @property:LLMDescription("One or two sentences justifying the confidence score from this persona's perspective")
    val rationale: String,
)

/** The structured output a single persona produces when it evaluates the [DomainModel]. */
@Serializable
@LLMDescription("A single persona's independent evaluation of the drafted domain model")
data class PersonaEvaluation(
    @property:LLMDescription("The persona's overall verdict on the model")
    val verdict: Verdict,
    @property:LLMDescription("Overall confidence from 0 to 100 across the whole model")
    val overallConfidence: Int,
    @property:LLMDescription("Per-bounded-context confidence scores; include one entry for every context")
    val assessments: List<ContextAssessment>,
    @property:LLMDescription("Specific concerns, each phrased as a concrete, actionable issue")
    val concerns: List<String>,
    @property:LLMDescription("Concrete counter-proposals that would raise this persona's confidence")
    val counterProposals: List<String>,
)

/** A persona paired with the evaluation it produced — the unit the consensus engine synthesizes. */
data class PersonaVerdict(
    val persona: PersonaDefinition,
    val evaluation: PersonaEvaluation,
)

/** One member of the pool: produces an independent [PersonaEvaluation] of a [DomainModel]. */
interface PersonaEvaluator {
    val persona: PersonaDefinition

    /**
     * Evaluate [domainModel]. [debateContext] is non-null on debate re-rounds: it carries the
     * current conflict report (and, in moderation, the human directive) so the persona can revise
     * its stance in light of the disagreement instead of evaluating cold.
     */
    suspend fun evaluate(domainModel: DomainModel, debateContext: String?): PersonaEvaluation
}

/** A [PersonaEvaluator] backed by a single structured LLM call at the persona's own temperature. */
class LlmPersonaEvaluator(
    override val persona: PersonaDefinition,
    private val executor: PromptExecutor,
    private val evaluationModel: LLModel,
    private val json: Json,
) : PersonaEvaluator {
    override suspend fun evaluate(domainModel: DomainModel, debateContext: String?): PersonaEvaluation {
        val contextNames = domainModel.boundedContexts.joinToString(", ") { it.name }
        val evaluationPrompt = prompt(
            id = "persona-${persona.id}",
            params = LLMParams(temperature = persona.temperature),
        ) {
            system(
                """
                ${persona.systemPrompt}

                Evaluate the domain model below STRICTLY from your perspective. Produce a confidence
                score (0–100) for every bounded context (${contextNames.ifBlank { "none drafted" }}),
                an overall verdict and confidence, the concrete concerns you have, and counter-proposals
                that would raise your confidence. Use BLOCK only for a hard, defensible blocker.
                """.trimIndent()
            )
            user("DRAFTED DOMAIN MODEL (JSON):\n${json.encodeToString(domainModel)}")
            if (debateContext != null) {
                user(
                    "DEBATE CONTEXT — the pool has not yet converged. Reconsider your stance in light " +
                        "of the following before answering:\n$debateContext"
                )
            }
        }
        return executor.executeStructured<PersonaEvaluation>(prompt = evaluationPrompt, model = evaluationModel)
            .getOrThrow().data
    }
}

/**
 * The decision pool. Broadcasts a [DomainModel] to every configured persona concurrently and joins
 * their evaluations. A single persona's failure is contained: it degrades to a neutral
 * [Verdict.APPROVE_WITH_CONCERNS] carrying the error as a concern, so one bad call never sinks the
 * whole round.
 */
class PersonaPool(
    private val config: PersonaPoolConfig,
    private val executor: PromptExecutor,
    private val evaluationModel: LLModel,
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    val size: Int get() = config.personas.size

    /** Fan out to every persona concurrently; [debateContext] is non-null only on debate re-rounds. */
    suspend fun evaluate(domainModel: DomainModel, debateContext: String? = null): List<PersonaVerdict> =
        coroutineScope {
            config.personas.map { persona ->
                async {
                    val evaluator = LlmPersonaEvaluator(persona, executor, evaluationModel, json)
                    runCatching { evaluator.evaluate(domainModel, debateContext) }
                        .map { PersonaVerdict(persona, it) }
                        .getOrElse { e ->
                            logger.error(e) { "Persona '${persona.id}' evaluation failed; recording neutral verdict." }
                            PersonaVerdict(persona, neutralFallback(domainModel, e))
                        }
                }
            }.awaitAll()
        }

    /** A neutral evaluation used when a persona's call fails, so the pool still produces a report. */
    private fun neutralFallback(domainModel: DomainModel, error: Throwable): PersonaEvaluation =
        PersonaEvaluation(
            verdict = Verdict.APPROVE_WITH_CONCERNS,
            overallConfidence = 50,
            assessments = domainModel.boundedContexts.map {
                ContextAssessment(it.name, 50, "Evaluation unavailable for this context.")
            },
            concerns = listOf("Persona evaluation failed: ${error.message ?: error::class.simpleName}"),
            counterProposals = emptyList(),
        )
}
