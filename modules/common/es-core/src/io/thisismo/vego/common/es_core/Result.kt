package io.thisismo.vego.common.es_core

sealed interface Result {
    data class Ack(val eventId: EventId, val serverSeq: Long) : Result
    data class Nack(val eventId: EventId, val reason: String) : Result
}