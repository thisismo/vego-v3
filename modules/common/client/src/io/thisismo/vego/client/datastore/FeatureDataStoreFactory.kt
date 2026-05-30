package io.thisismo.vego.client.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Provides the platform specific location where DataStore files are persisted.
 *
 * Implementations are supplied per platform (Android uses the app's files dir, iOS uses the
 * documents directory) so that the rest of the code can stay completely platform agnostic.
 */
interface DataStorePathProvider {
    /** [FileSystem] used to persist the DataStore files (`FileSystem.SYSTEM` on every platform). */
    val fileSystem: FileSystem

    /** Absolute path to the directory in which feature DataStore files live. */
    val baseDirectory: String
}

/**
 * Reusable, modular factory that creates one isolated, typed [DataStore] **per feature**.
 *
 * Any (future) feature module only depends on this factory and requests its own DataStore by
 * providing a unique [fileName], the [KSerializer] of its model and a [defaultValue]:
 *
 * ```
 * val userStore = factory.create("user", User.serializer(), defaultValue = null)
 * ```
 */
class FeatureDataStoreFactory(
    private val pathProvider: DataStorePathProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun <T> create(
        fileName: String,
        serializer: KSerializer<T>,
        defaultValue: T,
    ): DataStore<T> = DataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = pathProvider.fileSystem,
            serializer = KotlinxSerializationOkioSerializer(serializer, json, defaultValue),
            producePath = { "${pathProvider.baseDirectory}/$fileName.json".toPath() },
        ),
    )
}
