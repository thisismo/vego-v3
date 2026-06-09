package io.thisismo.vego.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

/**
 * The Consensus Engine (graph Node 4): it synthesizes the pool's independent [PersonaVerdict]s into a
 * single [ConflictReport] via a pluggable [ConsensusStrategy]. The report is deterministic given the
 * verdicts — no extra LLM call — so what the user moderates in HitL Pause 1 is a faithful aggregation
 * of the personas' confidence, not a re-hallucinated summary.
 *
 * Two strategies ship today — [WeightedMatrixConsensus] (the default) and [UnanimousGateConsensus] —
 * selected at launch via [ConsensusStrategy.fromEnvironment]; Round-Robin Iteration from the design
 * still slots in without touching the graph.
 */
interface ConsensusStrategy {
    /** Stable name shown in the conflict report (e.g. "weighted-matrix"). */
    val name: String

    /** Synthesize the pool's verdicts about [domainModel] into a conflict report. */
    fun synthesize(verdicts: List<PersonaVerdict>, domainModel: DomainModel): ConflictReport

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Environment variable selecting the strategy by name (`weighted-matrix` | `unanimous-gate`). */
        const val ENV_VAR: String = "ANALYST_CONSENSUS_STRATEGY"

        /**
         * Resolves the active strategy from the environment, mirroring [AnalystModelConfig.fromEnvironment]:
         * `ANALYST_CONSENSUS_STRATEGY` set to a strategy name picks it; unset or unknown keeps the
         * default [WeightedMatrixConsensus] (unknown names are logged). [getenv] is injectable for testing.
         */
        fun fromEnvironment(getenv: (String) -> String? = System::getenv): ConsensusStrategy {
            val id = getenv(ENV_VAR)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return WeightedMatrixConsensus()
            return when (id) {
                "weighted-matrix" -> WeightedMatrixConsensus()
                "unanimous-gate" -> UnanimousGateConsensus()
                else -> {
                    logger.warn { "Unknown consensus strategy '$id' for $ENV_VAR; keeping default weighted-matrix." }
                    WeightedMatrixConsensus()
                }
            }.also { logger.info { "Consensus strategy: ${it.name}." } }
        }
    }
}

/** One row of the confidence matrix: a single persona's headline stance, for the dashboard. */
@Serializable
data class PersonaScoreRow(
    val personaId: String,
    val role: String,
    val verdict: Verdict,
    val overallConfidence: Int,
)

/** The aggregated standing of a single bounded context across the whole pool. */
@Serializable
data class ContextConsensus(
    val boundedContext: String,
    /** Weight-averaged confidence (0–100) across the personas that cover this context. */
    val weightedConfidence: Double,
    /** True if a persona hard-blocked this context, or its weighted confidence is below threshold. */
    val blocking: Boolean,
    /** "Role: rationale" lines from the personas that scored this context low or blocked it. */
    val dissent: List<String>,
)

/**
 * The Conflict Report — the synthesized output of the Consensus Engine and the payload of HitL Pause 1.
 * It is intentionally a *matrix plus narrative*, not a verdict: the human moderates it, they do not
 * receive a binary approve/reject.
 */
@Serializable
data class ConflictReport(
    /** The strategy that produced this report. */
    val strategy: String,
    /** Weight-averaged overall confidence (0–100) across the pool. */
    val overallWeightedConfidence: Double,
    /** True when the pool cannot advance without human moderation (a hard block or sub-threshold context). */
    val deadlocked: Boolean,
    /** Per-bounded-context consensus, in model order. */
    val contexts: List<ContextConsensus>,
    /** Hard blockers raised by personas that returned [Verdict.BLOCK]. */
    val blockers: List<String>,
    /** Non-blocking concerns gathered across the pool. */
    val openConcerns: List<String>,
    /** Concrete counter-proposals the personas put on the table — the constructive half of the debate. */
    val counterProposals: List<String> = emptyList(),
    /** The confidence matrix — one row per persona — for the dashboard. */
    val matrix: List<PersonaScoreRow>,
)

/**
 * Weighted-matrix consensus: each persona's confidence is averaged per bounded context, weighted by
 * [PersonaDefinition.weight], and a context is flagged blocking if any covering persona hard-blocked
 * it or its weighted confidence falls below [blockThreshold]. The pool is deadlocked if any persona
 * returned [Verdict.BLOCK] or any context is blocking.
 */
class WeightedMatrixConsensus(
    private val blockThreshold: Double = 60.0,
) : ConsensusStrategy {
    override val name: String = "weighted-matrix"

    override fun synthesize(verdicts: List<PersonaVerdict>, domainModel: DomainModel): ConflictReport {
        val contexts = domainModel.boundedContexts.map { ctx ->
            val voters = verdicts.filter { it.persona.coversContext(ctx.name) }

            var weightSum = 0.0
            var weightedScore = 0.0
            val dissent = mutableListOf<String>()
            voters.forEach { v ->
                val assessment = v.assessmentFor(ctx.name)
                val confidence = assessment?.confidence ?: v.evaluation.overallConfidence
                val w = v.persona.weight
                weightSum += w
                weightedScore += w * confidence
                if (v.evaluation.verdict == Verdict.BLOCK || confidence < blockThreshold) {
                    dissent += "${v.persona.role}: ${assessment?.rationale ?: "blocked overall"}"
                }
            }

            val weighted = if (weightSum > 0.0) weightedScore / weightSum else 0.0
            val blocked = voters.any { it.evaluation.verdict == Verdict.BLOCK } || weighted < blockThreshold
            ContextConsensus(
                boundedContext = ctx.name,
                weightedConfidence = weighted.round1(),
                blocking = blocked,
                dissent = dissent,
            )
        }

        val deadlocked = verdicts.any { it.evaluation.verdict == Verdict.BLOCK } || contexts.any { it.blocking }
        return buildReport(name, verdicts, contexts, deadlocked)
    }
}

/**
 * Unanimous-gate consensus: the pool advances only when *every* persona approves outright. Any
 * [Verdict.BLOCK] or [Verdict.APPROVE_WITH_CONCERNS], or any per-context confidence below
 * [approveThreshold], deadlocks the pool — so anything short of unanimity falls through to the
 * bounded debate loop and then to human moderation. The weighted confidences are still computed
 * and reported (the dashboard matrix is unchanged); only the blocking/deadlock rule is stricter.
 */
class UnanimousGateConsensus(
    private val approveThreshold: Double = 75.0,
) : ConsensusStrategy {
    override val name: String = "unanimous-gate"

    override fun synthesize(verdicts: List<PersonaVerdict>, domainModel: DomainModel): ConflictReport {
        val contexts = domainModel.boundedContexts.map { ctx ->
            val voters = verdicts.filter { it.persona.coversContext(ctx.name) }

            var weightSum = 0.0
            var weightedScore = 0.0
            val dissent = mutableListOf<String>()
            voters.forEach { v ->
                val assessment = v.assessmentFor(ctx.name)
                val confidence = assessment?.confidence ?: v.evaluation.overallConfidence
                weightSum += v.persona.weight
                weightedScore += v.persona.weight * confidence
                if (v.evaluation.verdict != Verdict.APPROVE || confidence < approveThreshold) {
                    dissent += "${v.persona.role}: ${assessment?.rationale ?: "did not approve outright"}"
                }
            }

            ContextConsensus(
                boundedContext = ctx.name,
                weightedConfidence = (if (weightSum > 0.0) weightedScore / weightSum else 0.0).round1(),
                blocking = dissent.isNotEmpty(),
                dissent = dissent,
            )
        }

        val deadlocked = verdicts.any { it.evaluation.verdict != Verdict.APPROVE } || contexts.any { it.blocking }
        return buildReport(name, verdicts, contexts, deadlocked)
    }
}

// ---- Aggregation shared by every strategy: only the blocking/deadlock rule differs between them ----

/** The persona's per-context assessment for [contextName], if it produced one. */
private fun PersonaVerdict.assessmentFor(contextName: String): ContextAssessment? =
    evaluation.assessments.firstOrNull { it.boundedContext.equals(contextName, ignoreCase = true) }

/** Assembles the [ConflictReport] from the strategy-specific per-context consensus + deadlock flag. */
private fun buildReport(
    strategy: String,
    verdicts: List<PersonaVerdict>,
    contexts: List<ContextConsensus>,
    deadlocked: Boolean,
): ConflictReport {
    val totalWeight = verdicts.sumOf { it.persona.weight }
    val overall = if (totalWeight > 0.0) {
        verdicts.sumOf { it.persona.weight * it.evaluation.overallConfidence } / totalWeight
    } else 0.0

    return ConflictReport(
        strategy = strategy,
        overallWeightedConfidence = overall.round1(),
        deadlocked = deadlocked,
        contexts = contexts,
        blockers = verdicts
            .filter { it.evaluation.verdict == Verdict.BLOCK }
            .flatMap { v -> v.evaluation.concerns.map { "${v.persona.role}: $it" } }
            .distinct(),
        openConcerns = verdicts
            .filter { it.evaluation.verdict != Verdict.BLOCK }
            .flatMap { v -> v.evaluation.concerns.map { "${v.persona.role}: $it" } }
            .distinct(),
        counterProposals = verdicts
            .flatMap { v -> v.evaluation.counterProposals.map { "${v.persona.role}: $it" } }
            .distinct(),
        matrix = verdicts.map {
            PersonaScoreRow(it.persona.id, it.persona.role, it.evaluation.verdict, it.evaluation.overallConfidence)
        },
    )
}

private fun Double.round1(): Double = kotlin.math.round(this * 10.0) / 10.0
