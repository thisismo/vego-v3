package io.thisismo.vego.client.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource

/**
 * Generic, reusable [OkioSerializer] that (de)serializes any value [T] to JSON using
 * `kotlinx.serialization`.
 *
 * This keeps every feature DataStore free from boilerplate: a feature only needs to provide
 * the [KSerializer] of its model and a [defaultValue].
 */
class KotlinxSerializationOkioSerializer<T>(
    private val serializer: KSerializer<T>,
    private val json: Json,
    override val defaultValue: T,
) : OkioSerializer<T> {

    override suspend fun readFrom(source: BufferedSource): T =
        try {
            json.decodeFromString(serializer, source.readUtf8())
        } catch (exception: SerializationException) {
            throw CorruptionException("Unable to read value from DataStore", exception)
        }

    override suspend fun writeTo(t: T, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(serializer, t))
    }
}
