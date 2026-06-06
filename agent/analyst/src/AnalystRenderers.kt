package io.thisismo.vego.agent

/**
 * Plain-text/Markdown renderers for the artifacts the analyst session streams to the IDE chat, plus
 * the small affirmative-reply heuristic the finalize turn uses. These are pure presentation helpers,
 * kept separate from the phase machine in [KoogAnalystSession] so that file stays about control flow.
 *
 * Domain-model and consensus dashboards live in [ConsensusRenderers]; this file holds the validation
 * review (HitL Pause 2) and the long-term-memory memo.
 */

/** Whether the user's reply confirms finalization. */
internal fun isAffirmative(text: String): Boolean {
    val t = text.lowercase().trim()
    return "finalize" in t || "finalise" in t || "looks good" in t || "wrap it up" in t ||
        "approve" in t || "lgtm" in t || "ship it" in t || t == "yes" || t == "y" || t == "ok"
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
