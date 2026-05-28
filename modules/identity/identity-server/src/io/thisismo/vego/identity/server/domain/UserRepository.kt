package io.thisismo.vego.identity.server.domain

import io.thisismo.vego.identity.common.UserId

interface UserRepository {
    suspend fun findBySub(sub: String): User?
    suspend fun findByUserId(userId: UserId): User?
    suspend fun save(user: User)
}