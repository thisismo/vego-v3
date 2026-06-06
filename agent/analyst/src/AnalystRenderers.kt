package io.thisismo.vego.agent

/**
 * Plain-text/Markdown renderers for the artifacts the analyst session streams to the IDE chat, plus
 * the small affirmative-reply heuristic the finalize turn uses. These are pure presentation helpers,
 * kept separate from the phase machine in [KoogAnalystSession] so that file stays about control flow.
 */

/** Whether the user's reply confirms finalization. */
internal fun isAffirmative(text: String): Boolean {
    val t = text.lowercase().trim()
    return "finalize" in t || "finalise" in t || "looks good" in t || "wrap it up" in t ||
        "approve" in t || "lgtm" in t || "ship it" in t || t == "yes" || t == "y" || t == "ok"
}

internal fun renderRequirementsForm(draft: RequirementsDraft): String = buildString {
    appendLine("## Drafted requirements")
    appendLine()
    appendLine(draft.summary)
    appendLine()
    appendLine("### Epics")
    draft.epics.forEachIndexed { i, epic ->
        appendLine("${i + 1}. **${epic.title}** — ${epic.description}")
        epic.acceptanceCriteria.forEach { appendLine("   - [ ] $it") }
    }
    if (draft.outOfScope.isNotEmpty()) {
        appendLine()
        appendLine("### Out of scope")
        draft.outOfScope.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("### ❓ Please answer to continue (HitL form)")
    draft.clarifyingQuestions.forEach { q ->
        appendLine("- **${q.id}** (${q.kind}): ${q.question}")
        if (q.options.isNotEmpty()) appendLine("    options: ${q.options.joinToString(" | ")}")
    }
    appendLine()
    appendLine("_Reply with your answers (e.g. `q1: ...`, `q2: ...`) to start technical design._")
}

internal fun renderDraftText(draft: RequirementsDraft): String = buildString {
    appendLine("Summary: ${draft.summary}")
    appendLine("Epics:")
    draft.epics.forEach { epic ->
        appendLine("- ${epic.title}: ${epic.description}")
        epic.acceptanceCriteria.forEach { appendLine("    * AC: $it") }
    }
    if (draft.outOfScope.isNotEmpty()) appendLine("Out of scope: ${draft.outOfScope.joinToString("; ")}")
}

internal fun renderQuestions(draft: RequirementsDraft): String =
    draft.clarifyingQuestions.joinToString("\n") { q ->
        "${q.id} (${q.kind}): ${q.question}" + if (q.options.isNotEmpty()) " [${q.options.joinToString(" | ")}]" else ""
    }

internal fun renderReview(report: ValidationReport): String = buildString {
    appendLine(if (report.passed) "## ✅ Specifications ready for review" else "## ⚠️ Specifications drafted (validation found issues)")
    appendLine()
    appendLine("The following documents were written **directly into your working directory** — open them in the IDE Git tool window to diff them:")
    appendLine()
    report.files.forEach { appendLine("- `$it`") }
    appendLine()
    appendLine("### Validation")
    report.findings.forEach { appendLine("- $it") }
    appendLine()
    appendLine("_Reply **finalize** (or \"looks good\") to close the session for your manual commit, or reply with feedback to revise._")
}

internal fun renderMemo(memo: ArchitectureMemo): String = buildString {
    appendLine("# ${memo.title}")
    appendLine()
    appendLine("## Decisions")
    memo.decisions.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Constraints & non-goals")
    memo.constraints.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Components established")
    memo.components.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Documents")
    memo.documents.forEach { appendLine("- $it") }
}
