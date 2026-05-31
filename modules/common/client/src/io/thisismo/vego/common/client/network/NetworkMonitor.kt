package io.thisismo.vego.common.client.network

import kotlinx.coroutines.flow.StateFlow

enum class NetworkStatus { Unknown, Online, Offline }

interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
    suspend fun initialize(): Boolean
    fun close()
}