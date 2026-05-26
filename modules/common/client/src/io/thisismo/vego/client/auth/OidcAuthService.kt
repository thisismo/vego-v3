package io.thisismo.vego.client.auth

import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.tokenstore.TokenRefreshHandler
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import org.publicvalue.multiplatform.oidc.tokenstore.getTokens
import org.publicvalue.multiplatform.oidc.tokenstore.saveTokens

data class AuthTokens(val accessToken: String, val refreshToken: String)

@OptIn(ExperimentalOpenIdConnect::class)
class OidcAuthService(
    private val authFlowFactory: CodeAuthFlowFactory,
    private val tokenStore: TokenStore,
) : AuthService {
    suspend fun currentTokens(): AuthTokens? {
        return tokenStore.getTokens()?.let {
            AuthTokens(it.accessToken, it.refreshToken ?: throw IllegalStateException("Refresh token cannot be null"))
        }
    }

    override suspend fun login() {
        val flow = authFlowFactory.createAuthFlow(oidcClient)
        flow.startLogin()
        val tokens = flow.continueLogin()
        tokenStore.saveTokens(tokens)
    }

    override suspend fun hasStoredSession(): Boolean = tokenStore.getRefreshToken() != null

    override suspend fun logout() {
        oidcClient.endSession(tokenStore.getTokens()?.idToken ?: throw IllegalStateException("ID token cannot be null"))
    }
}