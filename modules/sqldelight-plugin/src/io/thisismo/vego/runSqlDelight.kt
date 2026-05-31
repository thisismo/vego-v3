package io.thisismo.vego

import app.cash.sqldelight.core.*
import app.cash.sqldelight.dialects.sqlite_3_38.SqliteDialect
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import java.util.*

/**
 * Runs the SQLDelight compiler over the `.sq`/`.sqm` files of the module the plugin is enabled in
 * and generates the corresponding Kotlin sources into [outputDirectory].
 *
 * SQLDelight is normally driven by its Gradle plugin which builds an in-memory project model and
 * feeds it to [SqlDelightEnvironment]. Here we recreate that minimal model by hand:
 *  - a single [SqlDelightCompilationUnit] whose source folders point at the `sqldelight`
 *    directories found under the module's source roots (`src`, `src@android`, ...) and whose
 *    output is [outputDirectory];
 *  - a [SqlDelightDatabaseProperties] describing the database (package, class name, flags).
 *
 * Source folders are discovered from [moduleRootDir] rather than the JVM-only
 * `${module.kotlinJavaSources}` reference, so that the plugin also works for Kotlin Multiplatform
 * modules (which have no JVM `main` source set).
 *
 * Behavior can be overridden per-module through a `sqldelight.properties` file in the module root
 * (a simple `key=value` file). Supported keys: `packageName`, `className`, `generateAsync`,
 * `expandSelectStar`, `verifyMigrations`, `deriveSchemaFromMigrations`, `treatNullAsUnknownForEquality`.
 * Any missing key falls back to the corresponding task argument.
 */
@TaskAction
fun runSqlDelight(
    @Input moduleRootDir: Path,
    moduleName: String = "Database",
    packageName: String = "io.thisismo.vego.database",
    verifyMigrations: Boolean = false,
    generateAsync: Boolean = true,
    expandSelectStar: Boolean = true,
    @Output outputDirectory: Path
) {
    val moduleRoot = moduleRootDir.toFile()
    val configuration = loadConfiguration(moduleRoot.resolve("sqldelight.properties"))
    val databasePackageName = configuration.getProperty("packageName", packageName)
    val databaseClassName = configuration.getProperty("className", moduleName)
    val asyncGeneration = configuration.getBoolean("generateAsync", generateAsync)
    val selectStarExpansion = configuration.getBoolean("expandSelectStar", expandSelectStar)
    val migrationsVerification = configuration.getBoolean("verifyMigrations", verifyMigrations)
    val schemaFromMigrations = configuration.getBoolean("deriveSchemaFromMigrations", false)
    val nullAsUnknownForEquality = configuration.getBoolean("treatNullAsUnknownForEquality", false)

    val outputDirectoryFile = outputDirectory.toFile()
    outputDirectoryFile.mkdirs()

    // Amper source roots are the `src` directory and its platform-qualified variants
    // (`src@android`, ...). We treat them as SQLDelight source folders directly, so that `.sq`/`.sqm`
    // files placed under `src/sqldelight/...` are assigned the package derived from their directory
    // (e.g. `src/sqldelight/User.sq` -> package `sqldelight`). SQLDelight requires every `.sq` file
    // to live in a package directory, so they cannot sit directly in a source root.
    val sqlDelightSourceFolders = (moduleRoot.listFiles()?.asSequence() ?: emptySequence())
        .filter { it.isDirectory && (it.name == "src" || it.name.startsWith("src@")) }
        .map { folder ->
            object : SqlDelightSourceFolder {
                override val folder: File = folder
                override val dependency: Boolean = false
            }
        }
        .toSet()

    // SQLDelight nests the generated database *implementation* in the sub-package
    // `<packageName>.<sanitized moduleName>`, so the module name must differ from the database class
    // name to avoid a `package conflicts with classifier` clash. We use the Amper module directory
    // name, which is unique within the project.
    val sqlDelightModuleName = moduleRoot.name

    val compilationUnit = object : SqlDelightCompilationUnit {
        override val name: String = sqlDelightModuleName
        override val sourceFolders: Set<SqlDelightSourceFolder> = sqlDelightSourceFolders
        override val outputDirectoryFile: File = outputDirectoryFile
    }

    val properties = object : SqlDelightDatabaseProperties {
        override val packageName: String = databasePackageName
        override val compilationUnits: List<SqlDelightCompilationUnit> = listOf(compilationUnit)
        override val className: String = databaseClassName
        override val dependencies: List<SqlDelightDatabaseName> = emptyList()
        override val deriveSchemaFromMigrations: Boolean = schemaFromMigrations
        override val treatNullAsUnknownForEquality: Boolean = nullAsUnknownForEquality
        override val rootDirectory: File = outputDirectoryFile
        override val generateAsync: Boolean = asyncGeneration
        override val expandSelectStar: Boolean = selectStarExpansion
    }

    val environment = SqlDelightEnvironment(
        properties = properties,
        compilationUnit = compilationUnit,
        verifyMigrations = migrationsVerification,
        dialect = SqliteDialect(),
        moduleName = sqlDelightModuleName,
    )

    when (val status = environment.generateSqlDelightFiles { message -> println(message) }) {
        SqlDelightEnvironment.CompilationStatus.Success -> Unit
        is SqlDelightEnvironment.CompilationStatus.Failure ->
            error("SQLDelight code generation failed:\n" + status.errors.joinToString(separator = "\n"))
    }
}

private fun loadConfiguration(propertiesFile: File): Properties {
    val properties = Properties()
    if (propertiesFile.isFile) {
        propertiesFile.bufferedReader().use(properties::load)
    }
    return properties
}

private fun Properties.getBoolean(key: String, default: Boolean): Boolean =
    getProperty(key)?.trim()?.toBooleanStrictOrNull() ?: default
