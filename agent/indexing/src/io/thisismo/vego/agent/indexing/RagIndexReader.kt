package io.thisismo.vego.agent.indexing

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The read side of the committed-design RAG index — the counterpart to [DocIndexer].
 *
 * Reads are deliberately lenient: the index is a machine-local file the indexer maintains
 * out-of-band, so a missing, empty, or partially-written file must degrade to "no context" rather
 * than fail the agent's hydration step.
 */
object RagIndexReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads the index under [workspaceRoot], or `null` if it is absent or unreadable. */
    fun read(workspaceRoot: Path): RagIndex? {
        val indexFile = workspaceRoot.resolve(RAG_INDEX_RELATIVE_PATH)
        if (!indexFile.isRegularFile()) return null
        return runCatching { json.decodeFromString(RagIndex.serializer(), indexFile.readText()) }.getOrNull()
    }
}
