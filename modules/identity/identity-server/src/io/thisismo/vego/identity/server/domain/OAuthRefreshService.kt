package io.thisismo.vego.identity.server.domain

interface OAuthRefreshService {
    suspend fun refreshTokens(refreshToken: String): OAuthTokens
}