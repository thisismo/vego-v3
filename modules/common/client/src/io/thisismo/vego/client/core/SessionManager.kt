package io.thisismo.vego.client.core

import io.thisismo.vego.client.auth.OidcAuthService
import io.thisismo.vego.client.io.BackendReachability
import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.publicvalue.multiplatform.oidc.types.Jwt
import kotlin.time.Clock

interface SessionState {
    data object Restoring : SessionState
    data object LoggedOut : SessionState
    data object Authenticated : SessionState
}

class SessionManager(
    private val authService: OidcAuthService,
    private val networkMonitor: NetworkMonitor,
    private val backendReachability: BackendReachability,
) {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Restoring)

    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    val offlineMode = !(networkMonitor.status.value === NetworkStatus.Online && backendReachability.isReachable.value)

    suspend fun initiateLogin() {
        try {
            authService.login()
        } catch (e: Exception) {
            _sessionState.update { SessionState.LoggedOut }
            return
        }
        _sessionState.update { SessionState.Authenticated }
    }

    suspend fun restoreSessionOffline() {
        if (!authService.hasStoredSession() || authService.currentTokens() == null) {
            _sessionState.update { SessionState.LoggedOut }
            return
        } else {
            val (_, refreshToken) = authService.currentTokens()!!
            val exp = Jwt.parse(refreshToken).payload.exp
            if (exp == null) {
                _sessionState.update { SessionState.LoggedOut }
                return
            }
            if (exp < Clock.System.now().toEpochMilliseconds()) {
                _sessionState.update { SessionState.LoggedOut }
            } else {
                _sessionState.update { SessionState.Authenticated }
            }
        }
    }

    suspend fun logout() {
        authService.logout()
        _sessionState.update { SessionState.LoggedOut }
    }
}