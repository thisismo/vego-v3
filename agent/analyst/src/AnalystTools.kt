package io.thisismo.vego.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.thisismo.vego.agent.indexing.IndexedDoc
import io.thisismo.vego.agent.indexing.RagIndexReader
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Domain-specific tools and retrieval helpers exposed to the agent.
 *
 * Generic filesystem and shell access come from Koog's built-in tools (`ReadFileTool`,
 * `WriteFileTool`, `EditFileTool`, `ListDirectoryTool`, `ExecuteShellCommandTool` — registered in
 * [KoogAnalystSession]); this file keeps only what is specific to the analyst workflow. All paths are
 * absolute — the workspace root is given to the model in the system prompt so it can resolve them.
 *
 * In the rethought, human-centric Git workflow the agent **never commits**: it writes specification
 * documents straight into the working directory (Workflow 1), then self-heals them against a
 * deterministic linter (Workflow 2) before handing off to the user. So this file backs:
 *  - Hydration — [hydrateDomainContext] retrieves prior context (memory + index + workspace docs);
 *  - Self-healing validation — [lintMarkdownDocs] reports broken links the agent then repairs;
 *  - Reconciliation — [specDocPaths]/[renderSpecDocInventory] tell the design/revise loop what is
 *    already staged, and [SpecDocTools] lets it *delete* a draft a rejected round left behind (Koog's
 *    built-ins cover read/write/edit but not deletion);
 *  - Finalize — [persistArchitectureMemory] distils a durable memo into long-term memory.
 * Nothing here commits or otherwise mutates Git history.
 */

// =====================================================================================
// Workflow 1 — Hydration / retrieval
// =====================================================================================

/** Workspace-relative directory where finalize (Workflow 3) writes long-term-memory memos. */
const val MEMORY_DIR_PATH: String = ".vego/memory"

/**
 * The workspace-relative memory directory for a given context [namespace]. The default (blank/null)
 * namespace is [MEMORY_DIR_PATH]; a named one isolates its memos under a sibling sub-directory, so the
 * agent can be pointed at a separate "context" (a different product line, an experiment) without its
 * memories bleeding across. Non-recursive reads keep the namespaces — and the `.archive` folder
 * `/forget` writes to — from leaking into each other.
 */
fun memoryDirFor(namespace: String?): String =
    if (namespace.isNullOrBlank()) MEMORY_DIR_PATH else "$MEMORY_DIR_PATH/$namespace"

/**
 * Workflow 1, Hydration node — retrieval that grounds the business analysis in what the project has
 * already established. It layers three sources, cheapest-first:
 *
 *  1. **Long-term memory** ([MEMORY_DIR_PATH]) — dense memos the agent distilled on finalize.
 *  2. **Committed-design RAG index** ([RagIndexReader]) — the incremental index the post-commit hook
 *     keeps in sync with what the user *actually committed* (titles/summaries of established specs).
 *  3. **Working-directory markdown** — a bounded walk of remaining `.md` docs for live context.
 *
 * (1) and (2) are the durable "memory" half of the loop closed by Workflows 3 and 4; (3) is the
 * file-based stand-in for a vector store. Swapping (3) for a true embeddings retriever — or pointing
 * (2) at a vector backend — is a drop-in change behind this single function.
 */
fun hydrateDomainContext(
    workspaceRoot: String,
    memorySubPath: String = MEMORY_DIR_PATH,
    committedDesignSection: String? = null,
    maxFiles: Int = 12,
    maxCharsPerFile: Int = 2_000,
): String {
    val root = Path(workspaceRoot)
    if (!root.exists() || !root.isDirectory()) return "No workspace context available."

    val sections = mutableListOf<String>()

    readLongTermMemory(root, memorySubPath)?.let { sections += it }
    // Prefer the caller's semantically-retrieved committed designs; fall back to the full catalogue.
    (committedDesignSection ?: readCommittedDesignIndex(root))?.let { sections += it }

    // Working-directory markdown, excluding the agent's own memory/index bookkeeping.
    val markdown = root.toFile()
        .walkTopDown()
        .onEnter { dir -> dir.name !in setOf(".git", "build", ".gradle", "node_modules", ".idea", ".vego") }
        .filter { it.isFile && it.extension.lowercase() in setOf("md", "markdown") }
        .filterNot { it.path.contains("/docs/.index/") }
        .take(maxFiles)
        .toList()

    if (markdown.isNotEmpty()) {
        sections += buildString {
            appendLine("WORKING-DIRECTORY DOCUMENTATION (uncommitted + committed markdown in the project):")
            markdown.forEach { file ->
                val rel = file.relativeTo(root.toFile()).path
                appendLine()
                appendLine("--- $rel ---")
                appendLine(file.readText().take(maxCharsPerFile))
            }
        }
    }

    return if (sections.isEmpty()) "No existing project context found in the workspace."
    else sections.joinToString("\n\n")
}

/** Reads the agent's long-term-memory memos (Workflow 3 output), most-recent first. */
private fun readLongTermMemory(root: java.nio.file.Path, memorySubPath: String = MEMORY_DIR_PATH, maxMemos: Int = 5): String? {
    val dir = root.resolve(memorySubPath)
    if (!dir.exists() || !dir.isDirectory()) return null
    val memos = dir.listDirectoryEntries("*.md")
        .filter { it.isRegularFile() }
        .sortedByDescending { it.fileName.toString() }
        .take(maxMemos)
    if (memos.isEmpty()) return null
    return buildString {
        appendLine("LONG-TERM MEMORY (architecture the agent has previously finalized):")
        memos.forEach { appendLine(); appendLine(it.readText().trim()) }
    }
}

/**
 * Renders the semantically-retrieved committed designs as a hydration section — the specifications
 * most relevant to the current idea (via [SemanticFactIndex]), leanest-relevant rather than the whole
 * catalogue, so the expensive domain-modeling prompt carries fewer, better-targeted tokens. Returns
 * null when there are no matches, so the caller falls back to the full-catalogue listing.
 */
fun renderRelevantCommittedDesigns(scored: List<ScoredFact>): String? {
    if (scored.isEmpty()) return null
    return buildString {
        appendLine("COMMITTED DESIGN INDEX (the specifications most relevant to this idea):")
        scored.forEach { (doc, _) ->
            append("- ${doc.path} — ${doc.title}")
            if (doc.summary.isNotBlank()) append(": ${doc.summary}")
            appendLine()
        }
    }
}

/**
 * Reads the committed-design RAG index that the post-commit hook (Workflow 4) maintains, via the
 * shared [RagIndexReader], and renders it as a compact catalogue of established specifications. The
 * reader degrades to `null` for a missing or unreadable index, so hydration never fails on it. Used
 * as the fallback when semantic retrieval yields nothing (no embedder match, or an embedding failure).
 */
private fun readCommittedDesignIndex(root: java.nio.file.Path): String? {
    val documents = RagIndexReader.read(root)?.documents?.takeIf { it.isNotEmpty() } ?: return null
    return buildString {
        appendLine("COMMITTED DESIGN INDEX (specifications already merged into the repository):")
        documents.entries.take(40).forEach { (path, doc) ->
            append("- $path — ${doc.title}")
            if (doc.summary.isNotBlank()) append(": ${doc.summary}")
            appendLine()
        }
    }
}

// =====================================================================================
// Workflow 2 — deterministic self-healing validation
// =====================================================================================

/** A single problem found in a drafted document. */
data class DocFinding(val file: String, val message: String)

/** Mermaid fenced code block: ```mermaid \n <body> \n ``` — captures the body for diagram validation. */
private val mermaidFenceRegex = Regex("""```mermaid\s*\n(.*?)```""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

/** Diagram types Mermaid recognizes; a fence whose first token isn't one of these won't render. */
private val mermaidDiagramKeywords = setOf(
    "graph", "flowchart", "sequenceDiagram", "classDiagram", "stateDiagram", "stateDiagram-v2",
    "erDiagram", "journey", "gantt", "pie", "mindmap", "timeline", "gitGraph", "quadrantChart",
    "requirementDiagram", "C4Context", "C4Container", "C4Component", "C4Dynamic", "C4Deployment",
)

/**
 * Validates every Markdown file under the given directories: flags empty files, broken relative
 * links (links whose target file does not exist on disk), and malformed Mermaid diagrams (empty
 * fences or an unrecognized diagram type — the C4 Context/Container diagrams are written as Mermaid).
 * External links, mail links and pure anchors are ignored. This is the deterministic checker that
 * backs both the agent-facing [lintMarkdownDocs] tool and the authoritative [ValidationReport] — the
 * "test" half of the self-healing loop, so the report the user eventually sees cannot be hallucinated.
 */
fun validateMarkdownDocs(directories: List<String>): Pair<List<String>, List<DocFinding>> {
    val linkRegex = Regex("""!?\[[^\]]*]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    val files = mutableListOf<String>()
    val findings = mutableListOf<DocFinding>()

    directories.map { Path(it) }.filter { it.exists() && it.isDirectory() }.forEach { dir ->
        dir.toFile().walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("md", "markdown") }
            .forEach { file ->
                files += file.absolutePath
                val text = file.readText()
                if (text.isBlank()) {
                    findings += DocFinding(file.absolutePath, "File is empty.")
                    return@forEach
                }
                linkRegex.findAll(text).forEach { match ->
                    val rawTarget = match.groupValues[1].trim()
                    val target = rawTarget.substringBefore('#')
                    if (target.isEmpty()) return@forEach // pure in-page anchor
                    if (target.startsWith("http://") || target.startsWith("https://") ||
                        target.startsWith("mailto:") || target.startsWith("/")
                    ) return@forEach
                    val resolved = file.toPath().parent?.resolve(target)?.normalize()
                    if (resolved == null || !resolved.exists()) {
                        findings += DocFinding(file.absolutePath, "Broken relative link: '$rawTarget'")
                    }
                }
                mermaidFenceRegex.findAll(text).forEach { match ->
                    val body = match.groupValues[1].trim()
                    if (body.isEmpty()) {
                        findings += DocFinding(file.absolutePath, "Empty Mermaid diagram block.")
                        return@forEach
                    }
                    val firstToken = body.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("%%") } // skip Mermaid comments
                        ?.substringBefore(' ')
                        .orEmpty()
                    if (mermaidDiagramKeywords.none { it.equals(firstToken, ignoreCase = true) }) {
                        findings += DocFinding(
                            file.absolutePath,
                            "Mermaid diagram has unrecognized type '$firstToken' (expected one of: " +
                                "${mermaidDiagramKeywords.joinToString(", ")}).",
                        )
                    }
                }
            }
    }
    return files to findings
}

@Tool(customName = "lint_markdown_docs")
@LLMDescription(
    "Validates every Markdown file under the given absolute directory for empty files, broken " +
        "relative links, and malformed Mermaid diagrams (empty or unrecognized C4/diagram type). " +
        "Returns 'OK — no issues found.' when clean, otherwise a numbered list of issues to fix. " +
        "Call this after writing docs and re-call it after each fix until it returns OK."
)
fun lintMarkdownDocs(
    @LLMDescription("Absolute path of the directory whose Markdown files should be validated") absoluteDirectory: String,
): String {
    val (_, findings) = validateMarkdownDocs(listOf(absoluteDirectory))
    if (findings.isEmpty()) return "OK — no issues found."
    return buildString {
        appendLine("${findings.size} issue(s) found:")
        findings.forEachIndexed { i, f -> appendLine("${i + 1}. ${f.file}: ${f.message}") }
    }
}

// =====================================================================================
// Workflow 2b — spec-doc reconciliation (inventory + scoped deletion)
// =====================================================================================

/**
 * Absolute paths of every Markdown spec document currently staged under the given directories.
 *
 * The design/revise loop uses this two ways: as the baseline captured before the first design round
 * (the *committed* docs that must be preserved), and as the live inventory of what is on disk now.
 * The set difference between them is exactly "the drafts this session staged" — the files a rejected
 * round must reconcile or delete instead of orphaning.
 */
fun specDocPaths(directories: List<String>): List<String> =
    directories.map { Path(it) }
        .filter { it.exists() && it.isDirectory() }
        .flatMap { dir ->
            dir.toFile().walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("md", "markdown") }
                .map { it.absolutePath }
                .toList()
        }
        .sorted()

/**
 * Renders [absolutePaths] as a compact, model-facing inventory — absolute path, workspace-relative
 * path, and the document's first heading — so the design task can decide, per file, whether to update
 * it in place, supersede it, or delete it. Returns null when the list is empty.
 */
fun renderSpecDocInventory(workspaceRoot: String, absolutePaths: List<String>): String? {
    if (absolutePaths.isEmpty()) return null
    val root = Path(workspaceRoot)
    return absolutePaths.joinToString("\n") { abs ->
        val path = Path(abs)
        val rel = runCatching { root.relativize(path).pathString }.getOrDefault(abs)
        val heading = runCatching {
            path.toFile().bufferedReader().useLines { lines -> lines.firstOrNull { it.startsWith("#") }?.trim() }
        }.getOrNull().orEmpty()
        "- $abs ($rel)${if (heading.isNotBlank()) " — $heading" else ""}"
    }
}

/**
 * The mutation tool that *removes* a staged spec document — the deletion primitive Koog's built-in
 * file tools omit. The design/revise loop uses it to retire an ADR (or other draft) that a previous,
 * rejected round wrote but the current decisions no longer need, so stale drafts can't accumulate as
 * orphans the validation report would then present as "passing".
 *
 * Deletion is hard-scoped to the [allowedDirectories] (the spec dirs) passed at construction: a
 * request to delete anything outside them is refused, so the tool can never be turned on the rest of
 * the workspace or on Git's own files — the agent still has no path to mutating history.
 */
class SpecDocTools(allowedDirectories: List<String>) : ToolSet {
    private val allowedRoots = allowedDirectories.map { Path(it).toAbsolutePath().normalize() }

    @Tool(customName = "delete_spec_doc")
    @LLMDescription(
        "Deletes a single staged specification document by absolute path. Use it to retire an ADR or " +
            "other draft that a previous, rejected round produced but the revised decisions no longer " +
            "need — do NOT recreate a rejected draft under a new name, delete it. Deletion is restricted " +
            "to the docs/adr, docs/c4 and docs/ux-specs directories; any path outside them is refused. " +
            "Returns a confirmation, or an error message to act on."
    )
    fun deleteSpecDoc(
        @LLMDescription("Absolute path of the spec document (.md) to delete") absolutePath: String,
    ): String {
        val target = runCatching { Path(absolutePath).toAbsolutePath().normalize() }
            .getOrElse { return "Refused: '$absolutePath' is not a valid path." }
        if (allowedRoots.none { target.startsWith(it) }) {
            return "Refused: '$absolutePath' is outside the spec directories; only documents under " +
                "docs/adr, docs/c4 and docs/ux-specs may be deleted."
        }
        if (!target.exists()) return "No file at '$absolutePath' — nothing to delete."
        if (!target.isRegularFile()) return "Refused: '$absolutePath' is not a regular file."
        return runCatching {
            target.deleteIfExists()
            "Deleted $absolutePath."
        }.getOrElse { e -> "Failed to delete '$absolutePath': ${e.message ?: e::class.simpleName}" }
    }
}

// =====================================================================================
// Workflow 3 — long-term memory persistence
// =====================================================================================

/**
 * Persists a finalized [ArchitectureMemo] rendered as Markdown into the agent's long-term memory
 * ([MEMORY_DIR_PATH]). The filename is timestamp-prefixed so [readLongTermMemory] can return the
 * most recent memos first. Returns the absolute path written.
 */
fun persistArchitectureMemory(
    workspaceRoot: String,
    sessionId: String,
    isoTimestamp: String,
    markdown: String,
    memorySubPath: String = MEMORY_DIR_PATH,
): String {
    val safeStamp = isoTimestamp.replace(Regex("[^0-9A-Za-z]"), "-")
    val safeSession = sessionId.take(8)
    val dir = Path(workspaceRoot).resolve(memorySubPath)
    dir.createDirectories()
    val file = dir.resolve("$safeStamp-$safeSession.md")
    file.writeText(markdown)
    return file.pathString
}

/**
 * Reset for the agent's long-term memory: instead of deleting the memos, it *moves* them into a
 * timestamped `.archive/<stamp>/` sub-folder of the same memory directory, so a `/forget` is always
 * recoverable. Non-recursive reads ([readLongTermMemory]) never see the archive, so future sessions
 * start clean. Returns the number of memos archived and the archive path (or 0/null when there were
 * none). The committed-design RAG index is intentionally left untouched — it tracks Git, not sessions.
 */
fun archiveLongTermMemory(workspaceRoot: String, memorySubPath: String, isoTimestamp: String): Pair<Int, String?> {
    val dir = Path(workspaceRoot).resolve(memorySubPath)
    if (!dir.exists() || !dir.isDirectory()) return 0 to null
    val memos = dir.listDirectoryEntries("*.md").filter { it.isRegularFile() }
    if (memos.isEmpty()) return 0 to null

    val safeStamp = isoTimestamp.replace(Regex("[^0-9A-Za-z]"), "-")
    val archive = dir.resolve(".archive").resolve(safeStamp)
    archive.createDirectories()
    memos.forEach { it.moveTo(archive.resolve(it.fileName.toString()), overwrite = true) }
    return memos.size to archive.pathString
}

// =====================================================================================
// Memory & persistent-facts inspection (the /memory list and /facts views)
// =====================================================================================

/**
 * One stored long-term memo, parsed back from its rendered Markdown for the `/memory list` view:
 * the title, the file it lives in, and the bullet count per `##` section (Decisions, Constraints, …).
 */
data class MemoEntry(val fileName: String, val title: String, val sectionCounts: Map<String, Int>)

/** Lists the memos in [memorySubPath], most-recent first, parsed into [MemoEntry] summaries. */
fun listLongTermMemory(workspaceRoot: String, memorySubPath: String, maxMemos: Int = 20): List<MemoEntry> {
    val dir = Path(workspaceRoot).resolve(memorySubPath)
    if (!dir.exists() || !dir.isDirectory()) return emptyList()
    return dir.listDirectoryEntries("*.md")
        .filter { it.isRegularFile() }
        .sortedByDescending { it.fileName.toString() }
        .take(maxMemos)
        .map { file ->
            val lines = runCatching { file.readText().lines() }.getOrDefault(emptyList())
            val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: file.fileName.toString()
            val counts = linkedMapOf<String, Int>()
            var section: String? = null
            for (line in lines) {
                when {
                    line.startsWith("## ") -> section = line.removePrefix("## ").trim().also { counts[it] = 0 }
                    section != null && line.trimStart().startsWith("- ") -> counts[section] = counts.getValue(section) + 1
                }
            }
            MemoEntry(file.fileName.toString(), title, counts)
        }
}

/**
 * The committed-design index entries — the agent's *persistent facts* — most-recently-indexed first,
 * or empty when the index is absent. This is the durable RAG catalogue the post-commit hook maintains
 * ([RagIndexReader]); it is the only persisted fact store besides the long-term memos. The dense
 * "facts" extracted by history compression during a turn are conversation-scoped and never written to
 * disk, so they are intentionally not listed here.
 */
fun listPersistentFacts(workspaceRoot: String): List<IndexedDoc> =
    RagIndexReader.read(Path(workspaceRoot))?.documents?.values
        ?.sortedByDescending { it.indexedAt }
        ?: emptyList()
