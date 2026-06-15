package io.thisismo.vego.common.es_core

import kotlinx.coroutines.flow.Flow

interface ClientEventStore<E : DomainEvent> : AggregateReader<E> {
    suspend fun append(events: List<E>): List<Recorded<E>>   // sync = Pending, assigns clientSeq
    suspend fun applyRemote(events: List<Recorded<E>>)       // pull lands here; dedup by eventId
    suspend fun unsynced(): List<Recorded<E>>                // sync is Pending
    suspend fun ack(acks: List<Result.Ack>)                  // Pending -> Synced
    suspend fun nack(nacks: List<Result.Nack>)               // Pending -> Rejected
    fun stream(fromClientSeq: Long): Flow<Recorded<E>>       // projection feed
}