package io.thisismo.vego.client.auth.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.thisismo.vego.client.auth.AuthService
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkStatus
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