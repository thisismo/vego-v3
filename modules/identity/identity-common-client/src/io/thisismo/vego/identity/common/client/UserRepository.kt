package io.thisismo.vego.identity.common.client

import io.thisismo.vego.identity.common.DietaryPreference
import io.thisismo.vego.identity.common.User
import io.thisismo.vego.identity.common.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * SQLDelight backed repository for the identity feature.
 *
 * Persists the currently logged in [User] in a local SQLite database (via [UserQueries]) so it can
 * be retrieved even while the device is offline. Built on top of the modular
 * [UserQueries]/[IdentityDatabase], so every other feature can follow the exact same pattern with
 * its own tables and database.
 *
 * Since only a single row is ever stored and every write goes through this repository, the observed
 * [user] is backed by a [MutableStateFlow] that is seeded from the database and kept in sync on each
 * [save]/[clear].
 */
class UserRepository(database: IdentityDatabase) : UserStore {

    private val queries: UserQueries = database.userQueries

    private val state: MutableStateFlow<User?> = MutableStateFlow(queries.selectUser()?.toDomain())

    /** Emits the cached [User], or `null` when none has been stored yet. */
    override val user: Flow<User?> = state.asStateFlow()

    /** Persists [user], overwriting any previously cached value. */
    override suspend fun save(user: User) {
        queries.upsert(user.toEntity())
        state.value = user
    }

    /** Removes the cached user (e.g. on logout). */
    override suspend fun clear() {
        queries.clear()
        state.value = null
    }

    private fun UserEntity.toDomain(): User = User(
        userId = UserId(Uuid.parse(userId)),
        name = name,
        dietaryPreference = DietaryPreference.valueOf(dietaryPreference),
    )

    private fun User.toEntity(): UserEntity = UserEntity(
        userId = userId.value.toString(),
        name = name,
        dietaryPreference = dietaryPreference.name,
    )
}
