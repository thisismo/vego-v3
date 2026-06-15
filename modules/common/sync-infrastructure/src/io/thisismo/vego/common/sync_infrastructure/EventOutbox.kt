package io.thisismo.vego.common.sync_infrastructure

import io.thisismo.vego.common.core.DomainEvent
import io.thisismo.vego.common.core.EventId
import kotlinx.coroutines.flow.Flow

interface EventOutbox<T> {
    suspend fun enqueue(event: T)
    suspend fun pending(): Flow<DomainEvent>
    suspend fun remove(eventId: EventId)
    suspend fun ack(eventId: EventId)
    suspend fun nack(eventId: EventId)
    suspend fun flushAll()
}