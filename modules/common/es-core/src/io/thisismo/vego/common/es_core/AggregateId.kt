package io.thisismo.vego.common.es_core

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class AggregateId<T>(val value: @Contextual T)