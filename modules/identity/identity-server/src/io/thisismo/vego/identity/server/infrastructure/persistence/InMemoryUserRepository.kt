package io.thisismo.vego.identity.server.infrastructure.persistence

import InMemoryRepository
import io.thisismo.vego.identity.common.UserId
import io.thisismo.vego.identity.server.domain.User
import io.thisismo.vego.identity.server.domain.UserRepository

internal class InMemoryUserRepository : InMemoryRepository<UserId, User>(
    { it.userId }
), UserRepository {
    override suspend fun findBySub(sub: String): User? = queryItems { it.sub == sub }.singleOrNull()
    override suspend fun findByUserId(userId: UserId): User? = get(userId)
    override suspend fun save(user: User) = put(user)
}
