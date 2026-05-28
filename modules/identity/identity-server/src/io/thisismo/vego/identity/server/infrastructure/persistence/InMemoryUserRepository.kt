package io.thisismo.vego.identity.server.infrastructure.persistence

import io.thisismo.vego.identity.server.domain.User
import io.thisismo.vego.identity.server.domain.UserRepository

internal class InMemoryUserRepository : InMemoryRepository<String, User>(
    { it.sub }
), UserRepository {
    override suspend fun findBySub(sub: String): User? = get(sub)

    override suspend fun save(user: User) = put(user)
}
