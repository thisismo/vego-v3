package io.thisismo.vego.client.auth.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.thisismo.vego.client.auth.infrastructure.network.NetworkMonitor
import io.thisismo.vego.client.auth.infrastructure.network.NetworkStatus
import io.thisismo.vego.client.auth.infrastructure.network.RpcConnectionManager
import io.thisismo.vego.client.auth.infrastructure.network.serviceFlow
import io.thisismo.vego.identity.common.IdentityApi
import io.thisismo.vego.identity.common.User
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

@Composable
fun AuthenticatedScreen() {
    Text("Authenticated Screen")
    val networkMonitor: NetworkMonitor = koinInject()
    val networkStatus by networkMonitor.status.collectAsState()
    val connectionManager = koinInject<RpcConnectionManager>(named("identity"))
    val identityApi by connectionManager.serviceFlow<IdentityApi>().collectAsState()
    var userInfo by remember { mutableStateOf<User?>(null) }

    LaunchedEffect(identityApi) {
        userInfo = identityApi?.getUserInfo()
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

            userInfo?.let { user ->
                BasicText("User Id: ${user.userId}")
                BasicText("Name: ${user.name}")
            }
        }
    }
}