package io.thisismo.vego.identity.common.client

/**
 * Row of the `user` table that persists the currently logged in user.
 *
 * Only a single user is ever cached, so the [id] is pinned to [SINGLE_ROW_ID] and any save simply
 * replaces the existing row.
 */
data class UserEntity(
    val id: Int = SINGLE_ROW_ID,
    val userId: String,
    val name: String,
    val dietaryPreference: String,
) {
    companion object {
        const val SINGLE_ROW_ID: Int = 0
    }
}
