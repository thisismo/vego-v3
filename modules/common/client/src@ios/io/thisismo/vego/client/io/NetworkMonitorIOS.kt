package io.thisismo.vego.client.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.DISPATCH_QUEUE_SERIAL
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.time.Duration.Companion.milliseconds

class NetworkMonitorIOS : NetworkMonitor {
    private val monitor = nw_path_monitor_create()
    @OptIn(ExperimentalForeignApi::class)
    private val queue = dispatch_queue_create(
        "io.thisismo.vego.network-monitor",
        null
    )

    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    override suspend fun initialize(): Boolean {
        val initialValue = CompletableDeferred<Boolean>()

        nw_path_monitor_set_update_handler(monitor) { path ->
            val online = nw_path_get_status(path) == nw_path_status_satisfied
            _status.value = if (online) NetworkStatus.Online else NetworkStatus.Offline
            if (!initialValue.isCompleted) initialValue.complete(online)
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)

        // Don't hang startup forever if the first path update is slow.
        // The flow will still update when the real value arrives.
        return withTimeoutOrNull(INITIAL_TIMEOUT_MS.milliseconds) { initialValue.await() } ?: false
    }

    override fun close() {
        nw_path_monitor_cancel(monitor)
    }

    private companion object {
        const val INITIAL_TIMEOUT_MS = 1_500L
    }
}