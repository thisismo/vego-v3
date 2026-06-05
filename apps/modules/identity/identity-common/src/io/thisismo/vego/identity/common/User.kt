package io.thisismo.vego.identity.common

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val userId: UserId,
    val name: String,
    val dietaryPreference: DietaryPreference
)