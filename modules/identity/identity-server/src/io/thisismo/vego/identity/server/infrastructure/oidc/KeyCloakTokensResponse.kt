package io.thisismo.vego.identity.server.infrastructure.oidc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KeyCloakTokensResponse(
    @SerialName("identity_token")
    val identityToken: String,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String
)
