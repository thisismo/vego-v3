package io.thisismo.vego.identity.server.domain

internal interface UserRepository {
    suspend fun findBySub(sub: String): User?
    suspend fun save(user: User)
}