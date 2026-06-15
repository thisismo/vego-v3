package io.thisismo.vego.common.es_core

import kotlinx.coroutines.flow.Flow

interface ServerEventStore<E : DomainEvent> : AggregateReader<E> {
    suspend fun commit(events: List<E>): List<Result>
    fun stream(fromServerSeq: Long): Flow<Recorded<E>>
}