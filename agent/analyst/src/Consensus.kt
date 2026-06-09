package io.thisismo.vego.agent

import kotlinx.serialization.Serializable

/**
 * The Consensus Engine (graph Node 4): it synthesizes the pool's independent [PersonaVerdict]s into a
 * single [ConflictReport] via a pluggable [ConsensusStrategy]. The report is deterministic given the
 * verdicts — no extra LLM call — so what the user moderates in HitL Pause 1 is a faithful aggregation
 * of the personas' confidence, not a re-hallucinated summary.
 *
 * v1 ships one strategy ([WeightedMatrixConsensus]); the interface exists so Round-Robin Iteration and
 * the Unanimous Gate from the design slot in without touching the graph.
 */
interface ConsensusStrategy {
    /** Stable name shown in the conflict report (e.g. "weighted-matrix"). */
    val name: String

    /** Synthesize the pool's verdicts about [domainModel] into a conflict report. */
    fun synthesize(verdicts: List<PersonaVerdict>, domainModel: DomainModel): ConflictReport
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
                val assessment = v.evaluation.assessments
                    .firstOrNull { it.boundedContext.equals(ctx.name, ignoreCase = true) }
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

        val totalWeight = verdicts.sumOf { it.persona.weight }
        val overall = if (totalWeight > 0.0) {
            verdicts.sumOf { it.persona.weight * it.evaluation.overallConfidence } / totalWeight
        } else 0.0

        val blockers = verdicts
            .filter { it.evaluation.verdict == Verdict.BLOCK }
            .flatMap { v -> v.evaluation.concerns.map { "${v.persona.role}: $it" } }
            .distinct()

        val openConcerns = verdicts
            .filter { it.evaluation.verdict != Verdict.BLOCK }
            .flatMap { v -> v.evaluation.concerns.map { "${v.persona.role}: $it" } }
            .distinct()

        val counterProposals = verdicts
            .flatMap { v -> v.evaluation.counterProposals.map { "${v.persona.role}: $it" } }
            .distinct()

        val deadlocked = verdicts.any { it.evaluation.verdict == Verdict.BLOCK } || contexts.any { it.blocking }

        return ConflictReport(
            strategy = name,
            overallWeightedConfidence = overall.round1(),
            deadlocked = deadlocked,
            contexts = contexts,
            blockers = blockers,
            openConcerns = openConcerns,
            counterProposals = counterProposals,
            matrix = verdicts.map {
                PersonaScoreRow(it.persona.id, it.persona.role, it.evaluation.verdict, it.evaluation.overallConfidence)
            },
        )
    }

    private fun Double.round1(): Double = kotlin.math.round(this * 10.0) / 10.0
}
