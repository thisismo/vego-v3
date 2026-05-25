package io.thisismo.vego.client.core

import io.thisismo.vego.client.io.NetworkMonitor

class ClientCore(private val networkMonitor: NetworkMonitor) {
    suspend fun initialize() {
        networkMonitor.initialize()
    }

    suspend fun tearDown() {
        networkMonitor.close()
    }
}