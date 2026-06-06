package io.thisismo.vego.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.thisismo.vego.agent.indexing.RagIndexReader
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
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
 *  - Finalize — [persistArchitectureMemory] distils a durable memo into long-term memory.
 * Nothing here commits or otherwise mutates Git history.
 */

// =====================================================================================
// Workflow 1 — Hydration / retrieval
// =====================================================================================

/** Workspace-relative directory where finalize (Workflow 3) writes long-term-memory memos. */
const val MEMORY_DIR_PATH: String = ".vego/memory"

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
fun hydrateDomainContext(workspaceRoot: String, maxFiles: Int = 12, maxCharsPerFile: Int = 2_000): String {
    val root = Path(workspaceRoot)
    if (!root.exists() || !root.isDirectory()) return "No workspace context available."

    val sections = mutableListOf<String>()

    readLongTermMemory(root)?.let { sections += it }
    readCommittedDesignIndex(root)?.let { sections += it }

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
private fun readLongTermMemory(root: java.nio.file.Path, maxMemos: Int = 5): String? {
    val dir = root.resolve(MEMORY_DIR_PATH)
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
 * Reads the committed-design RAG index that the post-commit hook (Workflow 4) maintains, via the
 * shared [RagIndexReader], and renders it as a compact catalogue of established specifications. The
 * reader degrades to `null` for a missing or unreadable index, so hydration never fails on it.
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

/**
 * Validates every Markdown file under the given directories: flags empty files and broken relative
 * links (links whose target file does not exist on disk). External links, mail links and pure
 * anchors are ignored. This is the deterministic checker that backs both the agent-facing
 * [lintMarkdownDocs] tool and the authoritative [ValidationReport] — the "test" half of the
 * self-healing loop, so the report the user eventually sees cannot be hallucinated.
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
            }
    }
    return files to findings
}

@Tool(customName = "lint_markdown_docs")
@LLMDescription(
    "Validates every Markdown file under the given absolute directory for empty files and broken " +
        "relative links. Returns 'OK — no issues found.' when clean, otherwise a numbered list of " +
        "issues to fix. Call this after writing docs and re-call it after each fix until it returns OK."
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
// Workflow 3 — long-term memory persistence
// =====================================================================================

/**
 * Persists a finalized [ArchitectureMemo] rendered as Markdown into the agent's long-term memory
 * ([MEMORY_DIR_PATH]). The filename is timestamp-prefixed so [readLongTermMemory] can return the
 * most recent memos first. Returns the absolute path written.
 */
fun persistArchitectureMemory(workspaceRoot: String, sessionId: String, isoTimestamp: String, markdown: String): String {
    val safeStamp = isoTimestamp.replace(Regex("[^0-9A-Za-z]"), "-")
    val safeSession = sessionId.take(8)
    val dir = Path(workspaceRoot).resolve(MEMORY_DIR_PATH)
    dir.createDirectories()
    val file = dir.resolve("$safeStamp-$safeSession.md")
    file.writeText(markdown)
    return file.pathString
}
