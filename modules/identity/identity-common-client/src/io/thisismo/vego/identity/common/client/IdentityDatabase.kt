package io.thisismo.vego.identity.common.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * SQLDelight backed database owned by the identity feature module.
 *
 * Because this is a standalone Amper project (without the SQLDelight Gradle plugin / code
 * generation), the schema and the queries are written by hand directly on top of the SQLDelight
 * runtime [SqlDriver]. Every feature ships its own database following this exact pattern; the app
 * simply builds it via the shared `SqlDriverFactory` and registers the resulting queries in Koin.
 */
class IdentityDatabase(driver: SqlDriver) {

    /** Type safe access to the `user` table. */
    val userQueries: UserQueries = UserQueries(driver)

    companion object {
        const val FILE_NAME: String = "identity.db"

        /**
         * The database schema: creates the single `user` table on first launch and would handle
         * migrations between schema versions.
         */
        val Schema: SqlSchema<QueryResult.Value<Unit>> = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 1

            override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                driver.execute(
                    identifier = null,
                    sql = """
                        |CREATE TABLE user (
                        |    id INTEGER NOT NULL PRIMARY KEY,
                        |    userId TEXT NOT NULL,
                        |    name TEXT NOT NULL,
                        |    dietaryPreference TEXT NOT NULL
                        |)
                    """.trimMargin(),
                    parameters = 0,
                )
                return QueryResult.Unit
            }

            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: app.cash.sqldelight.db.AfterVersion,
            ): QueryResult.Value<Unit> = QueryResult.Unit
        }
    }
}
