package io.thisismo.vego.client.core.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.thisismo.vego.common.client.network.NetworkMonitor
import io.thisismo.vego.common.client.network.NetworkStatus
import io.thisismo.vego.identity.common.client.UserService
import org.koin.compose.koinInject

@Composable
fun AuthenticatedScreen() {
    Text("Authenticated Screen")
    val networkMonitor: NetworkMonitor = koinInject()
    val networkStatus by networkMonitor.status.collectAsState()
    val userService: UserService = koinInject()
    // Offline-first: the UI always reads the user straight from the local database.
    val userInfo by userService.user.collectAsState(initial = null)

    // Fetch the latest user info from the backend after login and persist it locally.
    LaunchedEffect(Unit) {
        userService.refreshUser()
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