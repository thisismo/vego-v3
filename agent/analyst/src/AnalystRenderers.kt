package io.thisismo.vego.agent

import io.thisismo.vego.agent.indexing.IndexedDoc
import io.thisismo.vego.agent.indexing.RAG_INDEX_RELATIVE_PATH
import kotlin.math.roundToInt

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

/** Visualizes the long-term-memory memos of the active context for the `/memory list` command. */
internal fun renderMemoryEntries(contextLabel: String, entries: List<MemoEntry>): String = buildString {
    appendLine("## ${Ui.MEMORY} Long-term memory — $contextLabel context")
    appendLine()
    if (entries.isEmpty()) {
        append("_No memos stored yet — they're distilled and saved when you finalize a session._")
        return@buildString
    }
    appendLine("${entries.size} memo(s), most recent first:")
    appendLine()
    entries.forEachIndexed { i, e ->
        val counts = e.sectionCounts.entries
            .filter { it.value > 0 }
            .joinToString(", ") { "${it.value} ${it.key.lowercase()}" }
        appendLine("${i + 1}. **${e.title}**${if (counts.isNotBlank()) " — $counts" else ""}")
        appendLine("   _`${e.fileName}`_")
    }
}

/**
 * Visualizes the persistent committed-design RAG facts for the `/facts` command. [embeddedCount], when
 * given, reports how many docs already have a cached embedding (the rest embed on the next search).
 */
internal fun renderPersistentFacts(docs: List<IndexedDoc>, embeddedCount: Int? = null): String = buildString {
    appendLine("## 📚 Persistent facts — committed-design index")
    appendLine()
    appendLine(
        "_Source: `$RAG_INDEX_RELATIVE_PATH` — a plain-JSON catalogue (title + summary + headings per " +
            "doc) the post-commit hook rebuilds from the specs you actually commit. Together with " +
            "long-term memory it is one of the agent's two persistent stores; the dense facts from " +
            "in-turn history compression are conversation-scoped and never persisted._"
    )
    appendLine()
    if (docs.isEmpty()) {
        append("No committed-design facts indexed yet — the index fills in once you commit specs under `docs/`.")
        return@buildString
    }
    append("${docs.size} document(s)")
    if (embeddedCount != null) append(" · $embeddedCount embedded for semantic search (run `/facts <query>`)")
    appendLine(":")
    appendLine()
    docs.forEach { d ->
        append("- `${d.path}` — **${d.title}**")
        if (d.commit.isNotBlank()) append(" (`${d.commit.take(7)}`)")
        appendLine()
        if (d.summary.isNotBlank()) appendLine("   ${d.summary}")
    }
}

/** Visualizes the ranked results of a semantic `/facts <query>` search. */
internal fun renderFactSearch(query: String, results: List<ScoredFact>): String = buildString {
    appendLine("## 🔎 Committed-design facts — semantic matches for “$query”")
    appendLine()
    if (results.isEmpty()) {
        append("No matches — the committed-design index is empty.")
        return@buildString
    }
    results.forEach { (doc, similarity) ->
        val pct = (similarity.coerceIn(0.0, 1.0) * 100).roundToInt()
        append("- `${doc.path}` — **${doc.title}** · $pct% match")
        if (doc.commit.isNotBlank()) append(" (`${doc.commit.take(7)}`)")
        appendLine()
        if (doc.summary.isNotBlank()) appendLine("   ${doc.summary}")
    }
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
