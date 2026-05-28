package io.thisismo.vego.identity.server.application

import io.ktor.server.plugins.NotFoundException
import io.thisismo.vego.identity.common.DietaryPreference
import io.thisismo.vego.identity.common.UserId
import io.thisismo.vego.identity.server.domain.User
import io.thisismo.vego.identity.server.domain.UserRepository

internal class AuthService(
    private val userRepository: UserRepository
) {
    suspend fun getUser(sub: String): User {
        return userRepository.findBySub(sub) ?: throw NotFoundException("User not found")
    }

    suspend fun signUp(sub: String, preferredName: String, email: String): User {
        val newUser = User(
            sub = sub,
            userId = UserId.nextId(),
            name = preferredName,
            email = email,
            dietaryPreference = DietaryPreference.OMNIVORE
        )
        userRepository.save(newUser)
        return newUser
    }
}