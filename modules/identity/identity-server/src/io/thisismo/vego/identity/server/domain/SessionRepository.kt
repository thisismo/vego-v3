package io.thisismo.vego.identity.server.domain

import io.thisismo.vego.common.server.auth.SessionToken
import io.thisismo.vego.common.server.auth.UserSession

interface SessionRepository {
    suspend fun findByToken(token: SessionToken): UserSession?
    suspend fun save(userSession: UserSession)
    suspend fun delete(userSession: UserSession)
}