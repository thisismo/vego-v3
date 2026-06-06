package io.thisismo.vego.agent.indexing

import kotlinx.serialization.Serializable

/**
 * The on-disk shape of the committed-design RAG index — the durable "memory" of specifications the
 * user has actually committed. It is a plain JSON catalogue (title + summary + headings per
 * document) so it stays human-inspectable and trivial to diff; swapping it for an embeddings/vector
 * backend is a localized change behind [DocIndexer] (writer) and [RagIndexReader] (reader).
 *
 * This model is the single source of truth for the format. The `indexer` CLI writes it after each
 * commit; the `agent` reads it during hydration. They share nothing else.
 */

/** Workspace-relative path of the RAG index file. */
const val RAG_INDEX_RELATIVE_PATH: String = "docs/.index/rag-index.json"

@Serializable
data class RagIndex(
    val version: Int = 1,
    val documents: MutableMap<String, IndexedDoc> = mutableMapOf(),
)

@Serializable
data class IndexedDoc(
    val path: String,
    val title: String,
    val summary: String,
    val headings: List<String>,
    val commit: String,
    val chars: Int,
    val indexedAt: String,
)
