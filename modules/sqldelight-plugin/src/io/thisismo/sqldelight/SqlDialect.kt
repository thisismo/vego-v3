package io.thisismo.sqldelight

import app.cash.sqldelight.dialect.api.SqlDelightDialect
import org.jetbrains.amper.plugins.EnumValue

/**
 * The SQL dialect used to parse and type-check `.sq`/`.sqm` files.
 *
 * Each value corresponds to one of the dialect artifacts published by SQLDelight
 * (`app.cash.sqldelight:<dialect>-dialect`). The SQLite dialects are cumulative: newer versions
 * accept everything the older ones do plus the syntax added in that SQLite release.
 */
enum class SqlDialect {
    /** SQLite 3.18, the most widely compatible baseline (SQLDelight's recommended default). */
    @EnumValue("sqlite-3-18")
    SQLITE_3_18,

    /** SQLite 3.24, adds `UPSERT`. */
    @EnumValue("sqlite-3-24")
    SQLITE_3_24,

    /** SQLite 3.25, adds window functions and `ALTER TABLE ... RENAME COLUMN`. */
    @EnumValue("sqlite-3-25")
    SQLITE_3_25,

    /** SQLite 3.30, adds `NULLS FIRST`/`NULLS LAST` ordering. */
    @EnumValue("sqlite-3-30")
    SQLITE_3_30,

    /** SQLite 3.33, adds `UPDATE ... FROM`. */
    @EnumValue("sqlite-3-33")
    SQLITE_3_33,

    /** SQLite 3.35, adds `RETURNING` clauses and `ALTER TABLE ... DROP COLUMN`. */
    @EnumValue("sqlite-3-35")
    SQLITE_3_35,

    /** SQLite 3.38, adds JSON operators. */
    @EnumValue("sqlite-3-38")
    SQLITE_3_38,

    /** PostgreSQL, for JVM server modules using a JDBC or R2DBC driver. */
    @EnumValue("postgresql")
    POSTGRESQL,

    /** HSQLDB. */
    @EnumValue("hsql")
    HSQL,
}

internal fun SqlDialect.newInstance(): SqlDelightDialect = when (this) {
    SqlDialect.SQLITE_3_18 -> app.cash.sqldelight.dialects.sqlite_3_18.SqliteDialect()
    SqlDialect.SQLITE_3_24 -> app.cash.sqldelight.dialects.sqlite_3_24.SqliteDialect()
    SqlDialect.SQLITE_3_25 -> app.cash.sqldelight.dialects.sqlite_3_25.SqliteDialect()
    SqlDialect.SQLITE_3_30 -> app.cash.sqldelight.dialects.sqlite_3_30.SqliteDialect()
    SqlDialect.SQLITE_3_33 -> app.cash.sqldelight.dialects.sqlite_3_33.SqliteDialect()
    SqlDialect.SQLITE_3_35 -> app.cash.sqldelight.dialects.sqlite_3_35.SqliteDialect()
    SqlDialect.SQLITE_3_38 -> app.cash.sqldelight.dialects.sqlite_3_38.SqliteDialect()
    SqlDialect.POSTGRESQL -> app.cash.sqldelight.dialects.postgresql.PostgreSqlDialect()
    SqlDialect.HSQL -> app.cash.sqldelight.dialects.hsql.HsqlDialect()
}
