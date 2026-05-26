package io.thisismo.vego.identity.common

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

@JvmInline
@Serializable
value class UserId(val value: Uuid) {
    companion object {
        fun nextId(): UserId = UserId(Uuid.generateV7())
    }
}