package io.thisismo.vego.identity.common.client

import app.cash.sqldelight.db.SqlDriver
import sqldelight.UserQueries as GeneratedUserQueries

/**
 * Runtime query adapter for the identity feature's cached user.
 *
 * SQL statements are mirrored in `src/sqldelight/.../User.sq` and migration files so schema/query
 * ownership stays module-local and migration-ready. All statements are executed on the shared
 * runtime [SqlDriver].
 */
class UserQueries(driver: SqlDriver) {
    private val generated = GeneratedUserQueries(driver)

    /** Returns the cached [UserEntity], or `null` when none has been stored yet. */
    fun selectUser(): UserEntity? {
        return generated.selectUser().executeAsOneOrNull()?.let {
            UserEntity(
                id = it.id.toInt(),
                userId = it.userId,
                name = it.name,
                dietaryPreference = it.dietaryPreference,
            )
        }
    }

    /** Persists [entity], overwriting any previously cached value. */
    fun upsert(entity: UserEntity) {
        generated.upsert(
            id = entity.id.toLong(),
            userId = entity.userId,
            name = entity.name,
            dietaryPreference = entity.dietaryPreference,
        )
    }

    /** Removes the cached user (e.g. on logout). */
    fun clear() {
        generated.clear()
    }
}
