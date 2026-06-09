package io.thisismo.vego.agent

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import io.github.oshai.kotlinlogging.KotlinLogging
import io.thisismo.vego.agent.indexing.IndexedDoc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Workspace-relative path of the embedding sidecar that backs semantic `/facts` search. */
const val EMBEDDING_INDEX_RELATIVE_PATH: String = "docs/.index/embeddings.json"

/** A committed-design document scored against a query — the unit the `/facts <query>` view renders. */
data class ScoredFact(val doc: IndexedDoc, val similarity: Double)

/** One stored embedding, keyed by the doc's commit so an edit (new commit) triggers a re-embed. */
@Serializable
private data class EmbeddingEntry(val commit: String, val vector: List<Double>)

@Serializable
private data class EmbeddingSidecar(
    val version: Int = 1,
    /** The embedding model the vectors were produced with; a change invalidates the whole sidecar. */
    val model: String = "",
    val entries: MutableMap<String, EmbeddingEntry> = mutableMapOf(),
)

/**
 * The semantic upgrade of the committed-design RAG store.
 *
 * The post-commit indexer keeps the plain-JSON catalogue ([io.thisismo.vego.agent.indexing.RagIndex])
 * in sync with what was committed; it stays embedding-free so it never needs a network call or an API
 * key inside the git hook. This class is the *agent-side* companion that gives that catalogue true
 * semantic retrieval: it embeds each catalogue document once (with Koog's [Embedder]) and persists the
 * vectors as an inspectable JSON sidecar ([EMBEDDING_INDEX_RELATIVE_PATH]) next to the catalogue, then
 * ranks documents against a query by cosine similarity. Embeddings are computed lazily here — in the
 * agent process, where the API key already lives — and cached, so only new or edited docs are re-embedded.
 */
class SemanticFactIndex(
    private val embedder: Embedder,
    private val workspaceRoot: String,
    private val embeddingModelId: String,
) {
    private companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val sidecarPath = Path(workspaceRoot).resolve(EMBEDDING_INDEX_RELATIVE_PATH)

    /**
     * Ranks [docs] by semantic similarity to [query], embedding any not-yet-embedded (or edited) docs
     * first. Returns the top [topK] most similar, highest first.
     */
    suspend fun search(query: String, docs: List<IndexedDoc>, topK: Int = 5): List<ScoredFact> {
        if (docs.isEmpty()) return emptyList()
        val vectors = sync(docs)
        val queryVector = embedder.embed(query)
        return docs
            .mapNotNull { doc -> vectors[doc.path]?.let { ScoredFact(doc, queryVector.cosineSimilarity(it)) } }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    /** How many of [docs] currently have a cached embedding — surfaced in the `/facts` coverage line. */
    fun coverage(docs: List<IndexedDoc>): Int {
        val sidecar = load()
        if (sidecar.model != embeddingModelId) return 0
        return docs.count { sidecar.entries[it.path]?.commit == it.commit }
    }

    /** Embeds new/edited docs, prunes vectors for docs no longer in the catalogue, persists, returns the vectors. */
    suspend fun sync(docs: List<IndexedDoc>): Map<String, Vector> {
        // A model change invalidates every vector (dimensions/semantics differ), so start fresh.
        var sidecar = load()
        if (sidecar.model != embeddingModelId) sidecar = EmbeddingSidecar(model = embeddingModelId)

        val wantedPaths = docs.map { it.path }.toSet()
        var changed = sidecar.entries.keys.retainAll(wantedPaths)

        for (doc in docs) {
            val existing = sidecar.entries[doc.path]
            if (existing == null || existing.commit != doc.commit) {
                sidecar.entries[doc.path] = EmbeddingEntry(doc.commit, embedder.embed(embeddingText(doc)).values)
                changed = true
            }
        }

        if (changed) save(sidecar)
        return sidecar.entries.mapValues { (_, entry) -> Vector(entry.vector) }
    }

    /** The text a document is embedded from: its actual body when readable, else its catalogue metadata. */
    private fun embeddingText(doc: IndexedDoc): String {
        val body = runCatching { Path(workspaceRoot).resolve(doc.path).readText().take(MAX_EMBED_CHARS) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return body ?: listOf(doc.title, doc.summary, doc.headings.joinToString("; "))
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun load(): EmbeddingSidecar {
        if (!sidecarPath.isRegularFile()) return EmbeddingSidecar()
        return runCatching { json.decodeFromString(EmbeddingSidecar.serializer(), sidecarPath.readText()) }
            .getOrElse {
                logger.warn(it) { "Embedding sidecar at $sidecarPath was unreadable; rebuilding." }
                EmbeddingSidecar()
            }
    }

    private fun save(sidecar: EmbeddingSidecar) {
        runCatching {
            sidecarPath.parent?.createDirectories()
            sidecarPath.writeText(json.encodeToString(EmbeddingSidecar.serializer(), sidecar))
        }.onFailure { logger.warn(it) { "Could not persist embedding sidecar to $sidecarPath." } }
    }
}

/** Embedding inputs are capped so a very large spec doesn't blow the embedding model's token limit. */
private const val MAX_EMBED_CHARS = 6_000
