package io.thisismo.vego.identity.server

import co.touchlab.kermit.Logger
import io.thisismo.vego.identity.common.DietaryPreference
import io.thisismo.vego.identity.common.IdentityApi
import io.thisismo.vego.identity.common.User
import io.thisismo.vego.identity.common.UserId

class IdentityService(private val userId: UserId) : IdentityApi {
    private val logger = Logger.withTag("IdentityService")

    override suspend fun getUserInfo(): User {
        logger.d { "getUserInfo called for user $userId" }
        return User(userId, "John Doe", DietaryPreference.VEGETARIAN)
    }
}