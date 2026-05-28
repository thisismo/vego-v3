package io.thisismo.vego.identity.server.infrastructure.oidc

import kotlinx.serialization.Serializable

@Serializable
data class KeycloakOAuthConfig(
    val authorizeUrl: String,
    val accessTokenUrl: String,
    val clientId: String,
    val clientSecret: String
)