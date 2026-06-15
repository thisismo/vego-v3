package io.thisismo.vego.common.es_core

interface Aggregate<S, C, E : DomainEvent> {
    val initial: S
    fun decide(state: S, command: C): List<E>   // pure; throws on invalid command
    fun evolve(state: S, event: E): S            // pure fold step
}

context(effectiveOrder: Comparator<Recorded<DomainEvent>>)
fun <S, E : DomainEvent> Aggregate<S, *, E>.stateOf(records: List<Recorded<E>>): S =
    records
        .filter { it.sync !is SyncState.Rejected }   // rejected never affected state
        .sortedWith(effectiveOrder)
        .map { it.event }
        .fold(initial, ::evolve)

val clientEffectiveOrder: Comparator<Recorded<DomainEvent>> =
    compareBy<Recorded<DomainEvent>> { if (it.sync is SyncState.Synced) 0 else 1 }
        .thenBy { (it.sync as? SyncState.Synced)?.serverSeq ?: it.clientSeq }

val serverEffectiveOrder: Comparator<Recorded<DomainEvent>> =
    compareBy { (it.sync as SyncState.Synced).serverSeq }