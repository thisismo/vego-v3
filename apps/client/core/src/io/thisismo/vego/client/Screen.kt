package io.thisismo.vego.client

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import io.thisismo.vego.client.core.screens.AuthenticatedScreen
import io.thisismo.vego.client.core.auth.LoginScreen
import io.thisismo.vego.client.core.ClientCore
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.client.core.SessionState
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
        SessionState.Unauthenticated -> LoginScreen(koinInject())
        SessionState.Authenticated -> AuthenticatedScreen()
    }
}
