package io.thisismo.vego.common.client.persistence

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android implementation backed by [AndroidSqliteDriver], which stores the database inside the
 * app's private database directory.
 */
actual class SqlDriverFactory(private val context: Context) {

    actual fun createDriver(schema: SqlSchema<QueryResult.Value<Unit>>, fileName: String): SqlDriver =
        AndroidSqliteDriver(
            schema = schema,
            context = context.applicationContext,
            name = fileName,
        )
}
