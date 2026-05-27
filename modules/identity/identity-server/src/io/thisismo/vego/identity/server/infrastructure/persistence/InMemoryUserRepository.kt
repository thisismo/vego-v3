package io.thisismo.vego.identity.server.infrastructure.persistence

import io.thisismo.vego.identity.common.UserResponse
import io.thisismo.vego.identity.server.domain.User
import io.thisismo.vego.identity.server.domain.UserRepository

internal class InMemoryUserRepository : UserRepository {
    private val users = mutableMapOf<String, UserResponse>()

    override suspend fun findBySub(sub: String): UserResponse? {
        return users[sub]
    }

    override suspend fun save(user: User) {
        users[user.sub] = user
    }
}