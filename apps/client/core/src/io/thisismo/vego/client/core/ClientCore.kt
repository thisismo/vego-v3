package io.thisismo.vego.client.core

import co.touchlab.kermit.DefaultFormatter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import io.thisismo.vego.common.client.network.NetworkMonitor

class ClientCore(private val sessionManager: SessionManager, private val networkMonitor: NetworkMonitor) {
    suspend fun initialize() {
        Logger.setLogWriters(platformLogWriter(DefaultFormatter))
        networkMonitor.initialize()
        sessionManager.restoreSessionOffline()
    }

    suspend fun tearDown() {
        networkMonitor.close()
    }
}