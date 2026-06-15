package io.thisismo.vego.common.sync_infrastructure

interface EventStore<T> {
    fun append(event: T)
    fun replayEvents(fromSequence: Long): List<T>
    fun getLatestSequence(): Long
}