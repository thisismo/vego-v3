package io.thisismo.vego.identity.common.client

import app.cash.sqldelight.db.SqlDriver

/**
 * Hand written SQLDelight queries for the identity feature's cached user.
 *
 * Mirrors what the SQLDelight Gradle plugin would normally generate from a `.sq` file, but is
 * written manually because this standalone Amper project does not run the code generation plugin.
 * All statements are executed directly on the shared runtime [SqlDriver].
 */
class UserQueries(private val driver: SqlDriver) {

    /** Returns the cached [UserEntity], or `null` when none has been stored yet. */
    fun selectUser(): UserEntity? = driver.executeQuery(
        identifier = null,
        sql = "SELECT id, userId, name, dietaryPreference FROM user WHERE id = ${UserEntity.SINGLE_ROW_ID} LIMIT 1",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) {
                    UserEntity(
                        id = cursor.getLong(0)!!.toInt(),
                        userId = cursor.getString(1)!!,
                        name = cursor.getString(2)!!,
                        dietaryPreference = cursor.getString(3)!!,
                    )
                } else {
                    null
                }
            )
        },
        parameters = 0,
    ).value

    /** Persists [entity], overwriting any previously cached value. */
    fun upsert(entity: UserEntity) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO user (id, userId, name, dietaryPreference) VALUES (?, ?, ?, ?)",
            parameters = 4,
        ) {
            bindLong(0, entity.id.toLong())
            bindString(1, entity.userId)
            bindString(2, entity.name)
            bindString(3, entity.dietaryPreference)
        }
    }

    /** Removes the cached user (e.g. on logout). */
    fun clear() {
        driver.execute(identifier = null, sql = "DELETE FROM user", parameters = 0)
    }
}
