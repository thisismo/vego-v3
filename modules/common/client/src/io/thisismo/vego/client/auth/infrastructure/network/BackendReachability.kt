package io.thisismo.vego.client.auth.infrastructure.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BackendReachability(
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val _isReachable = MutableStateFlow(false)
    val isReachable: StateFlow<Boolean> = _isReachable.asStateFlow()

    private var consecutiveFailures = 0
    private val failureThreshold = 3

    init {
        // When transport comes back, give the backend a fresh chance.
        scope.launch {
            networkMonitor.status.collect { networkStatus ->
                if (networkStatus == NetworkStatus.Online) consecutiveFailures = 0
                else _isReachable.value = false
            }
        }
    }

    fun reportSuccess() {
        consecutiveFailures = 0
        _isReachable.value = true
    }

    fun reportFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            _isReachable.value = false
        }
    }
}