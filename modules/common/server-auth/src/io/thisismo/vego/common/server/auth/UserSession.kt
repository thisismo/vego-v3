package io.thisismo.vego.common.server.auth

import io.thisismo.vego.identity.common.UserId
import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val sub: String, val userId: UserId, val accessToken: String, val refreshToken: String)