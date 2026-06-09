package io.thisismo.vego.agent

/**
 * Plain-text/Markdown renderers for the artifacts the analyst session streams to the IDE chat, plus
 * the small affirmative-reply heuristic the finalize turn uses. These are pure presentation helpers,
 * kept separate from the phase machine in [KoogAnalystSession] so that file stays about control flow.
 *
 * Domain-model and consensus dashboards live in [ConsensusRenderers]; this file holds the validation
 * review (HitL Pause 2) and the long-term-memory memo.
 */

/** Short exact replies that count as "finalize". */
private val AFFIRMATIVE_EXACT = setOf("yes", "y", "yeah", "yep", "ok", "okay", "lgtm")

/** Affirmative phrases, matched on whole words so "disapprove" never reads as "approve". */
private val AFFIRMATIVE_PHRASES = listOf("finalize", "finalise", "looks good", "ship it", "wrap it up", "approve")

/** A leading or embedded negation that flips an otherwise-affirmative reply (e.g. "does not look good"). */
private val NEGATION = Regex("""\b(no|not|don'?t|doesn'?t|isn'?t|never|hold on|wait)\b""")

/**
 * Whether the user's reply confirms finalization. Deliberately strict: the HitL Pause 2 prompt tells
 * the user to reply **finalize** to close or to give feedback to revise, so anything ambiguous or
 * negated is treated as "revise" — the safe default that never finalizes against the user's intent.
 */
internal fun isAffirmative(text: String): Boolean {
    val t = text.lowercase().trim()
    if (t.isEmpty()) return false
    if (t in AFFIRMATIVE_EXACT) return true
    if (NEGATION.containsMatchIn(t)) return false
    return AFFIRMATIVE_PHRASES.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(t) }
}

internal fun renderReview(report: ValidationReport): String = buildString {
    appendLine(if (report.passed) "## ${Ui.APPROVE} Specifications ready for review" else "## ${Ui.WARN} Specifications drafted (validation found issues)")
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
