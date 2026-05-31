package io.thisismo.vego.identity.common.client

import io.thisismo.vego.common.client.persistence.SqlDriverFactory

/**
 * Builds the identity feature's [IdentityDatabase].
 *
 * Platform differences are fully handled by the shared [SqlDriverFactory] (Android uses the
 * `AndroidSqliteDriver`, iOS the `NativeSqliteDriver`), so this is plain common code: it just asks
 * the factory for a driver for [IdentityDatabase.Schema] / [IdentityDatabase.FILE_NAME] and wraps
 * it. This is exactly the small, modular shim every feature has to provide for its own database.
 */
internal fun createIdentityDatabase(factory: SqlDriverFactory): IdentityDatabase =
    IdentityDatabase(factory.createDriver(IdentityDatabase.Schema, IdentityDatabase.FILE_NAME))
