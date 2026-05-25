package io.thisismo.vego.client.core

import io.thisismo.vego.client.auth.AuthService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface SessionState {
    data object Restoring : SessionState
    data object LoggedOut : SessionState
    data class Authenticated(val offlineMode: Boolean) : SessionState
}

class SessionManager(
    private val authService: AuthService
) {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Restoring)

    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    suspend fun restoreSessionOnline() {

    }

    suspend fun refreshSession() {
        _sessionState.update { SessionState.Restoring }

    }
}