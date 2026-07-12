package io.thisismo.sqldelight

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

/**
 * Per-module SQLDelight configuration, set under `plugins.sqldelight` in `module.yaml`:
 *
 * ```yaml
 * plugins:
 *   sqldelight:
 *     enabled: true
 *     packageName: com.example.db
 *     dialect: sqlite-3-38
 * ```
 *
 * All settings have defaults, so `sqldelight: enabled` is enough to get started.
 */
@Configurable
interface SqlDelightSettings {
    /**
     * The package of the generated database interface and its implementation.
     *
     * Note that this is independent of the packages of the generated query classes: those are
     * derived from the directory a `.sq` file lives in, relative to the module's source root.
     * The default matches `.sq` files placed directly in `src/sqldelight/`.
     */
    val packageName: String get() = "sqldelight"

    /** The name of the generated database interface. */
    val databaseClassName: String get() = "Database"

    /**
     * The SQL dialect of the `.sq`/`.sqm` files. SQLite 3.18 is the most compatible baseline;
     * pick a newer SQLite version to use newer syntax, or a server dialect for JVM backends.
     */
    val dialect: SqlDialect get() = SqlDialect.SQLITE_3_18

    /**
     * Generate suspending query classes for use with asynchronous drivers
     * (for example the R2DBC or web worker drivers).
     */
    val generateAsync: Boolean get() = false

    /**
     * Build the database schema from the `.sqm` migration files instead of the `.sq` files.
     * Use this when the migrations are the source of truth for the table definitions.
     */
    val deriveSchemaFromMigrations: Boolean get() = false

    /** Type-check `.sqm` migration files during code generation and fail the build on errors. */
    val verifyMigrations: Boolean get() = false

    /**
     * Treat `expr == NULL` as `UNKNOWN` (SQL semantics) instead of rewriting it to `expr IS NULL`.
     */
    val treatNullAsUnknownForEquality: Boolean get() = false

    /**
     * Expand `SELECT *` into the explicit column list at compile time, so that query result types
     * only change when the query itself changes.
     */
    val expandSelectStar: Boolean get() = true

    /**
     * Extra directories to compile `.sq`/`.sqm` files from, in addition to the module's own
     * source roots. Relative paths are resolved against the module root, so a feature module can
     * include SQL that lives in another module, for example `../common/src`. The referenced module
     * should not run SQLDelight over the same files itself, or both modules will generate the
     * same classes.
     *
     * Each directory acts as a SQLDelight source folder: the package of the generated code is
     * derived from a file's path below it, so pointing at another module's source root keeps the
     * same packages as if the files were compiled there.
     */
    val additionalSourceDirectories: List<Path> get() = emptyList()
}
