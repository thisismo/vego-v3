package io.thisismo.vego.client.io

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.RequiresPermission
import io.thisismo.vego.client.auth.infrastructure.network.NetworkMonitor
import io.thisismo.vego.client.auth.infrastructure.network.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitorAndroid(context: Context) : NetworkMonitor {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override suspend fun initialize(): Boolean {
        // Track per-network validation state. A device may briefly have multiple
        // networks (e.g. Wi-Fi -> cellular handover); we are online if ANY of them
        // is validated.
        val validatedNetworks = mutableSetOf<Network>()

        fun recompute() {
            _status.value = if (validatedNetworks.isNotEmpty()) {
                NetworkStatus.Online
            } else {
                NetworkStatus.Offline
            }
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (usable) validatedNetworks.add(network)
                else validatedNetworks.remove(network)
                recompute()
            }

            override fun onLost(network: Network) {
                validatedNetworks.remove(network)
                recompute()
            }
        }
        callback = cb

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(request, cb)

        // Seed with current state so we don't have to wait for the first callback.
        val active = connectivityManager.activeNetwork
        val activeCaps = active?.let(connectivityManager::getNetworkCapabilities)
        val seededOnline = activeCaps != null &&
                activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (seededOnline && active != null) validatedNetworks.add(active)
        recompute()

        return _status.value == NetworkStatus.Online
    }

    override fun close() {
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
    }
}