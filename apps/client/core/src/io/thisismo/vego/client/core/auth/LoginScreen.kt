package io.thisismo.vego.client.core.auth

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.common.client.network.NetworkMonitor
import io.thisismo.vego.common.client.network.NetworkStatus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun LoginScreen(sessionManager: SessionManager) {
    val networkMonitor: NetworkMonitor = koinInject()
    val networkStatus by networkMonitor.status.collectAsState()

    var isAwaitingLogin by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Text("Login Screen")
    if (networkStatus != NetworkStatus.Online) {
        Text("Info: No internet connection")
    }
    Button(onClick = {
        coroutineScope.launch {
            isAwaitingLogin = true
            sessionManager.initiateLogin()
            isAwaitingLogin = false
        }
    }, enabled = !isAwaitingLogin && networkStatus == NetworkStatus.Online) {
        Text("Login")
    }
}