package io.thisismo.vego.agent.indexing

import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Only specifications under these workspace-relative roots are indexed. */
private val INDEXED_ROOTS = listOf("docs/adr/", "docs/ux-specs/")

/**
 * Workflow 4 — Post-Commit Synchronization.
 *
 * Maintains the committed-design RAG index ([RAG_INDEX_RELATIVE_PATH]) incrementally, one commit at
 * a time, so it stays in sync with what the user *actually* committed. The agent's hydration step
 * reads that index on the next feature request, so it "remembers" established designs.
 *
 * This is pure, process-agnostic logic: it shells out to `git` to learn what a commit touched and
 * rewrites the JSON catalogue. It is driven by the `indexer` CLI from a `post-commit` hook — there
 * is no server involved.
 */
object DocIndexer {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun index(repoPath: String, commit: String): String {
        val repo = File(repoPath)
        require(repo.isDirectory) { "Repository path is not a directory: $repoPath" }
        require(File(repo, ".git").exists()) { "Not a git repository: $repoPath" }

        val changes = changedFiles(repo, commit)
        val indexFile = File(repo, RAG_INDEX_RELATIVE_PATH)
        val index = loadIndex(indexFile)

        var added = 0
        var removed = 0
        for (change in changes) {
            if (!isIndexable(change.path)) continue
            when (change.status) {
                ChangeStatus.DELETED -> if (index.documents.remove(change.path) != null) removed++
                ChangeStatus.UPSERTED -> {
                    change.previousPath?.let { if (index.documents.remove(it) != null) removed++ }
                    val file = File(repo, change.path)
                    if (file.isFile) {
                        index.documents[change.path] = describe(file, change.path, commit)
                        added++
                    } else if (index.documents.remove(change.path) != null) {
                        removed++
                    }
                }
            }
        }

        if (added == 0 && removed == 0) return "no indexable spec changes"

        indexFile.parentFile?.mkdirs()
        indexFile.writeText(json.encodeToString(RagIndex.serializer(), index))
        return "$added upserted, $removed removed (${index.documents.size} total)"
    }

    private fun loadIndex(file: File): RagIndex =
        if (file.isFile) runCatching { json.decodeFromString(RagIndex.serializer(), file.readText()) }.getOrDefault(RagIndex())
        else RagIndex()

    private fun isIndexable(path: String): Boolean =
        INDEXED_ROOTS.any { path.startsWith(it) } && (path.endsWith(".md") || path.endsWith(".markdown"))

    private fun describe(file: File, relPath: String, commit: String): IndexedDoc {
        val text = file.readText()
        val lines = text.lineSequence().toList()
        val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: file.nameWithoutExtension
        val headings = lines.filter { it.trimStart().startsWith("#") }.map { it.trimStart().trimStart('#').trim() }
        val summary = lines.firstOrNull { line ->
            line.isNotBlank() && !line.trimStart().startsWith("#") && !line.trimStart().startsWith("-") &&
                !line.trimStart().startsWith("*") && !line.trimStart().startsWith(">")
        }?.trim()?.take(240).orEmpty()
        return IndexedDoc(
            path = relPath,
            title = title,
            summary = summary,
            headings = headings.take(20),
            commit = commit,
            chars = text.length,
            indexedAt = Instant.now().toString(),
        )
    }

    private enum class ChangeStatus { UPSERTED, DELETED }

    private data class Change(val status: ChangeStatus, val path: String, val previousPath: String? = null)

    /** Lists the files a commit touched, via `git show --name-status`. */
    private fun changedFiles(repo: File, commit: String): List<Change> {
        val output = runGit(
            repo,
            listOf("git", "show", "--no-commit-id", "--name-status", "--pretty=format:", commit),
        )
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                val code = parts.firstOrNull()?.firstOrNull() ?: return@mapNotNull null
                when (code) {
                    'D' -> parts.getOrNull(1)?.let { Change(ChangeStatus.DELETED, it) }
                    'R', 'C' -> {
                        val from = parts.getOrNull(1)
                        val to = parts.getOrNull(2) ?: return@mapNotNull null
                        Change(ChangeStatus.UPSERTED, to, previousPath = from)
                    }
                    else -> parts.getOrNull(1)?.let { Change(ChangeStatus.UPSERTED, it) }
                }
            }
            .toList()
    }

    private fun runGit(repo: File, command: List<String>): String {
        val process = ProcessBuilder(command).directory(repo).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("git command timed out: ${command.joinToString(" ")}")
        }
        check(process.exitValue() == 0) { "git command failed (${process.exitValue()}): $out" }
        return out
    }
}
