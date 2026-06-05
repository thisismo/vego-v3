package io.thisismo.vego.identity.common.client

import app.cash.sqldelight.db.SqlDriver
import sqldelight.Database

/**
 * SQLDelight backed database owned by the identity feature module.
 */
class IdentityDatabase(driver: SqlDriver) {

    /** Type safe access to the `user` table. */
    val userQueries: UserQueries = UserQueries(driver)

    companion object {
        const val FILE_NAME: String = "identity.db"

        val Schema = Database.Schema
    }
}
