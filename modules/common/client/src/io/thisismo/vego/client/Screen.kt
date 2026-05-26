package io.thisismo.vego.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.thisismo.vego.client.auth.screens.AuthenticatedScreen
import io.thisismo.vego.client.auth.screens.LoginScreen
import io.thisismo.vego.client.core.ClientCore
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.client.core.SessionState
import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkStatus
import org.koin.compose.koinInject

@Composable
fun Screen() {
    val clientCore: ClientCore = koinInject()
    val sessionManager: SessionManager = koinInject()
    val sessionState by sessionManager.sessionState.collectAsState()

    LaunchedEffect(Unit) {
        clientCore.initialize()
    }

    when (sessionState) {
        SessionState.Restoring -> Text("Restoring session...")
        SessionState.LoggedOut -> LoginScreen(koinInject())
        SessionState.Authenticated -> AuthenticatedScreen()
    }
}
