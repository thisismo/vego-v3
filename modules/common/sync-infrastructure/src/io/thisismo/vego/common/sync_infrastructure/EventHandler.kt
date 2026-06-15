package io.thisismo.vego.common.sync_infrastructure

import kotlinx.coroutines.flow.Flow

interface EventHandler<T> {
    fun handle(events: Flow<T>)
}