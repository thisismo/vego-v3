package io.thisismo.vego.agent

/**
 * Renderers for the domain model and the consensus dashboard.
 *
 * [renderConflictReport] is the heart of the "flat" HitL experience (Node 5 / Pause 1): the IDE shows
 * a confidence matrix and a debate summary instead of an Approve/Reject gate, and the human moderates
 * by injecting business context to break deadlocks. [renderDomainModel] gives a compact textual view
 * of the DDD model for seeding prompts and the finalize memo.
 */

/** Compact, prompt-friendly rendering of the drafted DDD model. */
internal fun renderDomainModel(model: DomainModel): String = buildString {
    appendLine("Summary: ${model.summary}")
    if (model.ubiquitousLanguage.isNotEmpty()) {
        appendLine("Ubiquitous language:")
        model.ubiquitousLanguage.forEach { appendLine("- ${it.term}: ${it.definition}") }
    }
    appendLine("Bounded contexts:")
    model.boundedContexts.forEach { ctx ->
        appendLine("- ${ctx.name}: ${ctx.purpose}")
        ctx.aggregateRoots.forEach { agg ->
            appendLine("    * aggregate ${agg.name} — ${agg.purpose}")
            if (agg.entities.isNotEmpty()) appendLine("        entities: ${agg.entities.joinToString(", ")}")
            if (agg.invariants.isNotEmpty()) appendLine("        invariants: ${agg.invariants.joinToString("; ")}")
        }
        if (ctx.integrationPoints.isNotEmpty()) {
            appendLine("    * integrates: ${ctx.integrationPoints.joinToString("; ")}")
        }
    }
    if (model.outOfScope.isNotEmpty()) appendLine("Out of scope: ${model.outOfScope.joinToString("; ")}")
}

/**
 * Renders the Conflict Report as the Consensus Dashboard for HitL Pause 1: a persona confidence
 * matrix, the per-context standing, the debate summary (blockers + open concerns + counter-proposals),
 * and a prompt for the human's tie-breaking directive. State routes back to the Consensus Engine after
 * the reply. [debateRounds] is the number of simulated-debate rounds the pool ran before settling
 * (0 when it converged on the first pass).
 */
internal fun renderConflictReport(model: DomainModel, report: ConflictReport, debateRounds: Int = 0): String = buildString {
    val status = if (report.deadlocked) "${Ui.DEADLOCK} Deadlock — moderation needed" else "${Ui.CONVERGED} Pool converged"
    appendLine("## Consensus dashboard — $status")
    appendLine()
    appendLine(
        "Strategy **${report.strategy}** · overall weighted confidence " +
            "**${report.overallWeightedConfidence}%** across ${report.matrix.size} personas."
    )
    if (debateRounds > 0) {
        val outcome = if (report.deadlocked) "still split after" else "settled after"
        appendLine("_The pool $outcome ${debateRounds} simulated-debate round(s)._")
    }
    appendLine()

    appendLine("### Domain model under review")
    model.boundedContexts.forEach { ctx ->
        appendLine("- **${ctx.name}** — ${ctx.purpose} (${ctx.aggregateRoots.size} aggregate root(s))")
    }
    appendLine()

    appendLine("### Persona confidence matrix")
    appendLine()
    appendLine("| Persona | Verdict | Confidence |")
    appendLine("| --- | --- | ---: |")
    report.matrix.forEach { row ->
        appendLine("| ${row.role} | ${verdictBadge(row.verdict)} | ${row.overallConfidence}% |")
    }
    appendLine()

    appendLine("### Bounded-context standing")
    appendLine()
    appendLine("| Bounded context | Weighted confidence | Status |")
    appendLine("| --- | ---: | --- |")
    report.contexts.forEach { ctx ->
        val flag = if (ctx.blocking) "⛔ blocking" else "✅ clear"
        appendLine("| ${ctx.boundedContext} | ${ctx.weightedConfidence}% | $flag |")
    }
    appendLine()

    if (report.blockers.isNotEmpty()) {
        appendLine("### ${Ui.BLOCK} Hard blockers")
        report.blockers.forEach { appendLine("- $it") }
        appendLine()
    }

    val dissent = report.contexts.flatMap { ctx -> ctx.dissent.map { "${ctx.boundedContext} — $it" } }
    if (dissent.isNotEmpty()) {
        appendLine("### Debate summary (dissent by context)")
        dissent.forEach { appendLine("- $it") }
        appendLine()
    }

    if (report.openConcerns.isNotEmpty()) {
        appendLine("### Open concerns")
        report.openConcerns.forEach { appendLine("- $it") }
        appendLine()
    }

    if (report.counterProposals.isNotEmpty()) {
        appendLine("### ${Ui.PROPOSAL} Counter-proposals on the table")
        report.counterProposals.forEach { appendLine("- $it") }
        appendLine()
    }

    appendLine("---")
    if (report.deadlocked) {
        appendLine(
            "**Your move:** provide a directive that injects the business context needed to break the " +
                "deadlock (e.g. _\"read-model latency wins over strict consistency for the Catalog context\"_). " +
                "I'll route it back to the consensus engine and proceed to technical design."
        )
    } else {
        appendLine(
            "**Your move:** the pool converged. Reply with any steering directive to refine the model, " +
                "or say **proceed** and I'll move to technical design (ADRs + C4 diagrams)."
        )
    }
}

private fun verdictBadge(verdict: Verdict): String = when (verdict) {
    Verdict.APPROVE -> "${Ui.APPROVE} approve"
    Verdict.APPROVE_WITH_CONCERNS -> "${Ui.CONCERNS} concerns"
    Verdict.BLOCK -> "${Ui.BLOCK} block"
}

/**
 * A terse, prompt-only digest of the consensus standing — the decisions, the blockers, and the
 * counter-proposals, without the dashboard's tables and prose. Used to seed the design and finalize
 * prompts so the standing travels as a few dense lines instead of the full Markdown dashboard the
 * *user* sees, keeping the model's context window small. The rich [renderConflictReport] stays the
 * face shown at HitL Pause 1.
 */
internal fun renderConsensusFacts(model: DomainModel, report: ConflictReport): String = buildString {
    val state = if (report.deadlocked) "deadlocked" else "converged"
    appendLine("Consensus ($state) — overall weighted confidence ${report.overallWeightedConfidence}% via ${report.strategy}.")
    appendLine("Bounded-context standing:")
    report.contexts.forEach { ctx ->
        val flag = if (ctx.blocking) "BLOCKING" else "clear"
        appendLine("- ${ctx.boundedContext}: ${ctx.weightedConfidence}% ($flag)")
    }
    if (report.blockers.isNotEmpty()) {
        appendLine("Hard blockers:")
        report.blockers.forEach { appendLine("- $it") }
    }
    if (report.counterProposals.isNotEmpty()) {
        appendLine("Counter-proposals:")
        report.counterProposals.forEach { appendLine("- $it") }
    }
}
