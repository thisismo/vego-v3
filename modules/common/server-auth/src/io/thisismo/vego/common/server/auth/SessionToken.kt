package io.thisismo.vego.common.server.auth

import kotlinx.serialization.Serializable
import java.util.UUID

@JvmInline
@Serializable
value class SessionToken(val token: String) {
    companion object {
        fun generate(): SessionToken {
            return SessionToken(UUID.randomUUID().toString())
        }
    }
}