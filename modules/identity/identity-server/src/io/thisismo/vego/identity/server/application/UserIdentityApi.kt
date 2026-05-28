package io.thisismo.vego.identity.server.application

import io.thisismo.vego.identity.common.UserId
import io.thisismo.vego.identity.common.client.IdentityApi
import io.thisismo.vego.identity.server.domain.UserRepository

class UserIdentityApi(
    private val userId: UserId,
    private val userRepository: UserRepository
) : IdentityApi {
    override suspend fun getUserName(): String {
        return userRepository.findByUserId(userId)?.name ?: throw IllegalArgumentException("User not found")
    }
}