package io.thisismo.vego.common.es_core

data class Recorded<out E : DomainEvent>(
    val event: E,
    val clientSeq: Long,
    val sync: SyncState,
)