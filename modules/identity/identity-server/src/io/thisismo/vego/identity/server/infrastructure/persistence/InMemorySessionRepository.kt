package io.thisismo.vego.identity.server.infrastructure.persistence

import io.thisismo.vego.common.server.auth.SessionToken
import io.thisismo.vego.common.server.auth.UserSession
import io.thisismo.vego.identity.server.domain.SessionRepository

internal class InMemorySessionRepository : InMemoryRepository<SessionToken, UserSession>(
    { it.token }
), SessionRepository {
    override suspend fun findByToken(token: SessionToken): UserSession? = get(token)

    override suspend fun save(userSession: UserSession) = put(userSession)

    override suspend fun delete(userSession: UserSession) {
        remove(userSession)
    }
}
