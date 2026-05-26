package io.thisismo.vego.identity.common

import kotlinx.rpc.annotations.Rpc

@Rpc
interface UserApi {
    suspend fun getUserById(userId: UserId): User?
    suspend fun createUser(dietaryPreference: DietaryPreference): UserId
}