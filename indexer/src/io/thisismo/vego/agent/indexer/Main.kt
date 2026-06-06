package io.thisismo.vego.agent.indexer

import io.thisismo.vego.agent.indexing.DocIndexer
import kotlin.system.exitProcess

/**
 * Workflow 4 — Post-Commit Synchronization entrypoint.
 *
 * Invoked by `.git/hooks/post-commit` (via `run-indexer.sh`) with the repository root and the commit
 * just created. It incrementally re-indexes exactly the specification Markdown that commit touched
 * into the local RAG index, then exits. There is no long-running process and no server.
 *
 * Usage: `indexer <repoPath> <commitHash>`
 *
 * Like the post-commit hook itself, failures must never disrupt the user's commit: any error is
 * logged to stderr and the process still exits 0.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: indexer <repoPath> <commitHash>")
        exitProcess(2)
    }
    val repo = args[0]
    val commit = args[1]

    runCatching { DocIndexer.index(repo, commit) }
        .onSuccess { summary -> System.err.println("indexed commit $commit: $summary") }
        .onFailure { e -> System.err.println("indexing failed for commit $commit: ${e.message}") }

    // Always succeed — indexing is best-effort background bookkeeping.
    exitProcess(0)
}
