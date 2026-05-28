package io.thisismo.vego.identity.server.infrastructure.oidc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.thisismo.vego.identity.server.domain.OAuthRefreshService
import io.thisismo.vego.identity.server.domain.OAuthTokens

class OAuthRefreshServiceKtor(
    private val httpClient: HttpClient,
    private val config: KeycloakOAuthConfig
) : OAuthRefreshService {
    override suspend fun refreshTokens(refreshToken: String): OAuthTokens {
        val tokens = httpClient.post(config.accessTokenUrl) {
            setBody(mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret
            ))
        }.body<KeyCloakTokensResponse>()

        return OAuthTokens(
            identityToken = tokens.identityToken,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken
        )
    }
}