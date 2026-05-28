package io.thisismo.vego.client.core

import co.touchlab.kermit.Logger
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

/**
 * Narrow contract for "the place that receives a session token from the
 * identity-callback deeplink". Exists primarily so the deeplink layer doesn't
 * have to depend on the full [SessionManager] (and its OIDC/network graph)
 * and can be exercised with a fake in unit tests.
 */
interface SessionTokenReceiver {
    fun onSessionTokenReceived(sessionToken: String)
}

class SessionManager(
    private val authService: OidcAuthService,
    private val networkMonitor: NetworkMonitor,
    private val backendReachability: BackendReachability,
) : SessionTokenReceiver {
    private val log = Logger.withTag("SessionManager")
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Restoring)

    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    val offlineMode = !(networkMonitor.status.value === NetworkStatus.Online && backendReachability.isReachable.value)

    suspend fun initiateLogin() {
        log.i("Initiating login...")
        try {
            authService.login()
        } catch (e: Exception) {
            log.e("Failed to initiate login: ${e.message}", e)
            _sessionState.update { SessionState.LoggedOut }
            return
        }
        _sessionState.update { SessionState.Authenticated }
        log.i("Login successful")
    }

    /**
     * Called by the deeplink layer when the identity server has redirected back
     * into the app with a freshly issued session token. Promotes the session to
     * [SessionState.Authenticated]. The token itself is currently only logged;
     * persistence will be handled once a dedicated token store API lands.
     */
    override fun onSessionTokenReceived(sessionToken: String) {
        if (sessionToken.isEmpty()) {
            log.w("Ignoring empty session token from identity callback")
            return
        }
        log.i("Session token received via identity callback (len=${sessionToken.length})")
        _sessionState.update { SessionState.Authenticated }
    }

    suspend fun restoreSessionOffline() {
        if (!authService.hasStoredSession() || authService.currentTokens() == null) {
            log.i("No session to restore from login...")
            _sessionState.update { SessionState.LoggedOut }
            return
        } else {
            val (_, refreshToken) = authService.currentTokens() ?: throw IllegalStateException("No tokens found in storage")
            val exp = Jwt.parse(refreshToken).payload.exp ?: throw IllegalStateException("Refresh token missing expiration claim")
            val nowSeconds = Clock.System.now().epochSeconds
            log.i("Stored Session found. Refresh Token Expiry: $exp Clock: $nowSeconds Expired? ${exp < nowSeconds}")
            if (exp < nowSeconds) {
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