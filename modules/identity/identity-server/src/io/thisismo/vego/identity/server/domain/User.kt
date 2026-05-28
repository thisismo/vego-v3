package io.thisismo.vego.identity.server.domain

import io.thisismo.vego.identity.common.DietaryPreference
import io.thisismo.vego.identity.common.UserId

data class User(
    val userId: UserId,
    val sub: String,
    val email: String,
    val dietaryPreference: DietaryPreference,
    val name: String,
)