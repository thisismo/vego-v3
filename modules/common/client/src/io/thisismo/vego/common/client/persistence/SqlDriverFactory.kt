package io.thisismo.vego.common.client.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Platform specific factory that knows how to create a [SqlDriver] for a given database file name.
 *
 * The actual implementation is provided per platform (Android needs a `Context` and uses the
 * `AndroidSqliteDriver`, while iOS uses the `NativeSqliteDriver` which stores its files inside the
 * app's documents directory). This keeps feature modules almost completely platform agnostic: a
 * feature only provides its own [SqlSchema] (the `CREATE TABLE` statements) and wraps the resulting
 * driver in its own queries.
 */
expect class SqlDriverFactory {

    /**
     * Creates a [SqlDriver] for the database described by [schema] and persisted under [fileName].
     */
    fun createDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver
}
