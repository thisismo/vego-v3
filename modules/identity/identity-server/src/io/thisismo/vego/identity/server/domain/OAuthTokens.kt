package io.thisismo.vego.identity.server.domain

data class OAuthTokens(
    val identityToken: String,
    val accessToken: String,
    val refreshToken: String
)