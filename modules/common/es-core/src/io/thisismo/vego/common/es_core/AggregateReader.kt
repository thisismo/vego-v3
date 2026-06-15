package io.thisismo.vego.common.es_core

interface AggregateReader<E : DomainEvent> {
    suspend fun forAggregate(id: AggregateId<*>): List<Recorded<E>>
}
