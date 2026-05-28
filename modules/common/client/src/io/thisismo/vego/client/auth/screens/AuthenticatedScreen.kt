package io.thisismo.vego.client.auth.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkStatus
import io.thisismo.vego.identity.common.client.IdentityApi
import org.koin.compose.koinInject

@Composable
fun AuthenticatedScreen() {
    Text("Authenticated Screen")
    val networkMonitor: NetworkMonitor = koinInject()
    val networkStatus by networkMonitor.status.collectAsState()
    val identityApi = koinInject<IdentityApi>()
    var userName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        userName = identityApi.getUserName()
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("Hello, ${when (networkStatus) {
                NetworkStatus.Online -> "online"
                NetworkStatus.Offline -> "offline"
                NetworkStatus.Unknown -> "unknown"
            }}!")

            userName?.let { user ->
                BasicText("Name: $userName")
            }
        }
    }
}