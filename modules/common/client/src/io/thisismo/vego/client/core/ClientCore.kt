package io.thisismo.vego.client.core

import io.thisismo.vego.client.io.NetworkMonitor

class ClientCore(private val sessionManager: SessionManager, private val networkMonitor: NetworkMonitor) {

    suspend fun initialize() {
        networkMonitor.initialize()
        sessionManager.restoreSessionOffline()
    }

    suspend fun tearDown() {
        networkMonitor.close()
    }
}