package io.thisismo.vego.common.server.auth

import io.thisismo.vego.identity.common.UserId
import kotlin.time.Instant

data class UserSession(
    val token: SessionToken,
    val userId: UserId,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Instant
)