package io.thisismo.vego.common.es_core

import io.thisismo.vego.identity.common.UserId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface DomainEvent {
    val eventId: EventId
    val userId: UserId
    val timestamp: Instant
    val aggregateId: AggregateId<*>
}

@Serializable
@JvmInline
value class EventId(val value: Uuid) {
    companion object {
        fun random(): EventId = EventId(Uuid.generateV7())
    }
}
