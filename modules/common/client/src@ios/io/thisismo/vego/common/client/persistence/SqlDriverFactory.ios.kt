package io.thisismo.vego.common.client.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * iOS implementation backed by [NativeSqliteDriver], which stores the database inside the app's
 * documents directory so that the data survives app restarts and is available while offline.
 */
actual class SqlDriverFactory {

    actual fun createDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver =
        NativeSqliteDriver(schema = schema, name = fileName)
}
