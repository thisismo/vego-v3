package io.thisismo.vego.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConsensusTest {
    private fun persona(id: String, weight: Double = 1.0) = PersonaDefinition(
        id = id,
        role = "The $id",
        focus = "testing",
        temperature = 0.3,
        weight = weight,
        boundedContexts = emptyList(),
        systemPrompt = "You are $id.",
    )

    private fun model(vararg contexts: String) = DomainModel(
        summary = "test model",
        ubiquitousLanguage = emptyList(),
        boundedContexts = contexts.map { BoundedContext(it, "purpose", emptyList(), emptyList()) },
        outOfScope = emptyList(),
    )

    private fun verdict(id: String, verdict: Verdict, confidence: Int, contexts: List<String>, weight: Double = 1.0) =
        PersonaVerdict(
            persona(id, weight),
            PersonaEvaluation(
                verdict = verdict,
                overallConfidence = confidence,
                assessments = contexts.map { ContextAssessment(it, confidence, "rationale of $id") },
                concerns = listOf("concern of $id"),
                counterProposals = emptyList(),
            ),
        )

    // ---- WeightedMatrixConsensus ----

    @Test
    fun weightedMatrixAdvancesOnConfidentConcerns() {
        val m = model("Ordering")
        val verdicts = listOf(
            verdict("a", Verdict.APPROVE, 90, listOf("Ordering")),
            verdict("b", Verdict.APPROVE_WITH_CONCERNS, 80, listOf("Ordering")),
        )
        val report = WeightedMatrixConsensus().synthesize(verdicts, m)
        assertFalse(report.deadlocked)
        assertEquals("weighted-matrix", report.strategy)
        assertEquals(85.0, report.contexts.single().weightedConfidence)
    }

    @Test
    fun weightedMatrixDeadlocksOnBlock() {
        val m = model("Ordering")
        val verdicts = listOf(
            verdict("a", Verdict.APPROVE, 90, listOf("Ordering")),
            verdict("b", Verdict.BLOCK, 20, listOf("Ordering")),
        )
        val report = WeightedMatrixConsensus().synthesize(verdicts, m)
        assertTrue(report.deadlocked)
        assertTrue(report.contexts.single().blocking)
        assertTrue(report.blockers.any { it.contains("concern of b") })
    }

    @Test
    fun weightedMatrixWeightsConfidencePerPersona() {
        val m = model("Ordering")
        val verdicts = listOf(
            verdict("light", Verdict.APPROVE, 100, listOf("Ordering"), weight = 1.0),
            verdict("heavy", Verdict.APPROVE, 70, listOf("Ordering"), weight = 3.0),
        )
        val report = WeightedMatrixConsensus().synthesize(verdicts, m)
        // (1*100 + 3*70) / 4 = 77.5
        assertEquals(77.5, report.contexts.single().weightedConfidence)
    }

    // ---- UnanimousGateConsensus ----

    @Test
    fun unanimousGateDeadlocksOnAnyConcern() {
        val m = model("Ordering")
        val verdicts = listOf(
            verdict("a", Verdict.APPROVE, 95, listOf("Ordering")),
            verdict("b", Verdict.APPROVE_WITH_CONCERNS, 90, listOf("Ordering")),
        )
        val report = UnanimousGateConsensus().synthesize(verdicts, m)
        assertTrue(report.deadlocked, "anything short of unanimous APPROVE must deadlock the gate")
        assertEquals("unanimous-gate", report.strategy)
        assertTrue(report.contexts.single().blocking)
        assertTrue(report.contexts.single().dissent.any { it.startsWith("The b:") })
    }

    @Test
    fun unanimousGateDeadlocksBelowApproveThreshold() {
        val m = model("Ordering")
        val verdicts = listOf(
            verdict("a", Verdict.APPROVE, 95, listOf("Ordering")),
            verdict("b", Verdict.APPROVE, 70, listOf("Ordering")), // APPROVE, but under the 75 gate
        )
        val report = UnanimousGateConsensus().synthesize(verdicts, m)
        assertTrue(report.deadlocked)
    }

    @Test
    fun unanimousGateAdvancesOnUnanimousApproval() {
        val m = model("Ordering", "Billing")
        val verdicts = listOf(
            verdict("a", Verdict.APPROVE, 95, listOf("Ordering", "Billing")),
            verdict("b", Verdict.APPROVE, 80, listOf("Ordering", "Billing")),
        )
        val report = UnanimousGateConsensus().synthesize(verdicts, m)
        assertFalse(report.deadlocked)
        assertTrue(report.contexts.none { it.blocking })
    }

    // ---- Strategy selection from the environment ----

    @Test
    fun strategySelectionDefaultsToWeightedMatrix() {
        assertIs<WeightedMatrixConsensus>(ConsensusStrategy.fromEnvironment { null })
    }

    @Test
    fun strategySelectionHonoursEnvVar() {
        val env = mapOf(ConsensusStrategy.ENV_VAR to "unanimous-gate")
        assertIs<UnanimousGateConsensus>(ConsensusStrategy.fromEnvironment(env::get))
    }

    @Test
    fun strategySelectionKeepsDefaultOnUnknownName() {
        val env = mapOf(ConsensusStrategy.ENV_VAR to "majority-rules")
        assertIs<WeightedMatrixConsensus>(ConsensusStrategy.fromEnvironment(env::get))
    }
}
