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
        return User(
            userId,
            listOf(
                "John Doe",
                "Jane Doe",
                "Alice",
                "Bob",
                "Eve",
                "Charlie",
                "David",
                "Ella",
                "Frank",
                "Grace",
                "Hannah",
                "Isaac",
                "Jack",
                "Katie",
                "Liam",
                "Mia",
                "Nathan",
                "Olivia",
                "Peter",
                "Quinn",
                "Rachel",
                "Sam",
                "Tina",
                "Uma",
                "Victor",
                "Wendy",
                "Xander",
                "Yara",
                "Zach"
            ).random(),
            DietaryPreference.VEGETARIAN
        )
    }
}