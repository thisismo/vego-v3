package io.thisismo.vego.common.es_core

sealed interface SyncState {
    data object Pending : SyncState
    data class Synced(val serverSeq: Long) : SyncState
    data class Rejected(val reason: String) : SyncState
}