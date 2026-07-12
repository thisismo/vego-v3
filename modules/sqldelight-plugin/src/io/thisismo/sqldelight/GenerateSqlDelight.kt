package io.thisismo.sqldelight

import app.cash.sqldelight.core.SqlDelightCompilationUnit
import app.cash.sqldelight.core.SqlDelightDatabaseName
import app.cash.sqldelight.core.SqlDelightDatabaseProperties
import app.cash.sqldelight.core.SqlDelightEnvironment
import app.cash.sqldelight.core.SqlDelightFileIndex
import app.cash.sqldelight.core.SqlDelightSourceFolder
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path

/**
 * Runs the SQLDelight compiler over the `.sq`/`.sqm` files of the module the plugin is enabled in
 * and writes the generated Kotlin sources to [outputDirectory].
 *
 * SQLDelight is normally driven by its Gradle plugin, which builds an in-memory project model and
 * feeds it to [SqlDelightEnvironment]. This task action recreates that minimal model by hand: a
 * single [SqlDelightCompilationUnit] covering all of the module's source roots, plus a
 * [SqlDelightDatabaseProperties] describing the database (package, class name, flags).
 *
 * The source roots (`src`, `src@android`, ...) are discovered from [moduleRootDir] rather than
 * taken from the built-in `${module.kotlinJavaSources}` reference, because the latter is JVM-only
 * and this plugin also targets Kotlin Multiplatform modules. [additionalSourceDirectories] are
 * compiled in as well, which allows a module to include SQL sources that live in another module.
 * Every source folder participates the same way: the package of a `.sq` file is derived from its
 * directory path below the folder, so `src/sqldelight/User.sq` produces `sqldelight.UserQueries`.
 * SQLDelight requires every `.sq` file to live in a package directory; they cannot sit directly
 * in a source folder.
 *
 * The output directory is fully replaced on each run so that files generated for deleted or
 * renamed `.sq` files do not linger.
 */
@TaskAction
fun generateSqlDelight(
    @Input moduleRootDir: Path,
    @Input additionalSourceDirectories: List<Path> = emptyList(),
    @Output outputDirectory: Path,
    moduleName: String,
    packageName: String = "sqldelight",
    databaseClassName: String = "Database",
    dialect: SqlDialect = SqlDialect.SQLITE_3_18,
    generateAsync: Boolean = false,
    deriveSchemaFromMigrations: Boolean = false,
    verifyMigrations: Boolean = false,
    treatNullAsUnknownForEquality: Boolean = false,
    expandSelectStar: Boolean = true,
) {
    requireValidPackageName(packageName)

    // SQLDelight nests the generated database implementation in the sub-package
    // `<packageName>.<sanitized module name>`, so the two names must differ to avoid a
    // "package conflicts with classifier" clash in the generated code.
    val implementationPackage = SqlDelightFileIndex.sanitizeDirectoryName(moduleName)
    require(implementationPackage.isNotEmpty()) {
        "Module name '$moduleName' contains no letters or digits and cannot be used to name the generated sub-package."
    }
    require(implementationPackage != databaseClassName) {
        "databaseClassName '$databaseClassName' collides with the generated implementation package " +
            "'$packageName.$implementationPackage'. Choose a different databaseClassName."
    }

    val moduleRoot = moduleRootDir.toFile()
    val sourceFolders = sqlDelightSourceFolders(moduleRoot, additionalSourceDirectories)

    // Replace the previous output wholesale: the compiler only ever adds files, so leftovers from
    // deleted or renamed .sq files would otherwise stay on the compile classpath.
    val outputDir = outputDirectory.toFile()
    outputDir.deleteRecursively()
    outputDir.mkdirs()

    // Stdout on purpose: the toolchain logs stderr at error level, and this is not an error.
    if (sourceFolders.none { it.folder.containsSqlDelightSources() }) {
        println(
            "Warning: SQLDelight is enabled for module '$moduleName' but no .sq or .sqm files were " +
                "found under its source directories. Skipping code generation.",
        )
        return
    }

    val compilationUnit = CompilationUnit(
        name = implementationPackage,
        sourceFolders = sourceFolders,
        outputDirectoryFile = outputDir,
    )

    val properties = DatabaseProperties(
        packageName = packageName,
        className = databaseClassName,
        compilationUnits = listOf(compilationUnit),
        deriveSchemaFromMigrations = deriveSchemaFromMigrations,
        treatNullAsUnknownForEquality = treatNullAsUnknownForEquality,
        generateAsync = generateAsync,
        expandSelectStar = expandSelectStar,
        rootDirectory = moduleRoot,
    )

    val environment = SqlDelightEnvironment(
        properties = properties,
        compilationUnit = compilationUnit,
        verifyMigrations = verifyMigrations,
        dialect = dialect.newInstance(),
        moduleName = moduleName,
    )

    when (val status = environment.generateSqlDelightFiles(logger = {})) {
        is SqlDelightEnvironment.CompilationStatus.Failure -> {
            val report = status.errors.joinToString(separator = "\n")
            error("SQLDelight code generation failed for module '$moduleName':\n$report")
        }
        SqlDelightEnvironment.CompilationStatus.Success -> {
            val fileCount = outputDir.walkTopDown().count { it.isFile }
            println("Generated $fileCount Kotlin file(s) for database $packageName.$databaseClassName")
        }
    }
}

/**
 * The module's source roots — the `src` directory and its platform-qualified variants
 * (`src@android`, ...) — plus the configured additional directories, used directly as SQLDelight
 * source folders. Exact duplicates are dropped; a folder nested inside another is rejected,
 * because SQLDelight would process the files below it twice.
 */
private fun sqlDelightSourceFolders(moduleRoot: File, additional: List<Path>): Set<SqlDelightSourceFolder> {
    val sourceRoots = moduleRoot.listFiles().orEmpty()
        .filter { it.isDirectory && (it.name == "src" || it.name.startsWith("src@")) }
        .sortedBy { it.name }
    val additionalDirs = additional.map { moduleRoot.toPath().resolve(it).toFile() }
    additionalDirs.forEach {
        require(it.isDirectory) {
            "additionalSourceDirectories entry '$it' does not exist or is not a directory."
        }
    }

    val folders = (sourceRoots + additionalDirs).map { it.canonicalFile }.distinct()
    folders.forEach { folder ->
        folders.forEach { other ->
            require(folder == other || !folder.startsWith(other)) {
                "Source directory '$folder' is nested inside '$other'; their files would be compiled twice. " +
                    "Remove the nested entry from additionalSourceDirectories."
            }
        }
    }

    return folders.map(::SourceFolder).toSet()
}

private fun File.containsSqlDelightSources(): Boolean =
    walkTopDown().any { it.isFile && (it.extension == "sq" || it.extension == "sqm") }

private fun requireValidPackageName(packageName: String) {
    val segment = Regex("[A-Za-z_][A-Za-z0-9_]*")
    require(packageName.split('.').all { it.matches(segment) }) {
        "packageName '$packageName' is not a valid package name."
    }
}

private class SourceFolder(override val folder: File) : SqlDelightSourceFolder {
    override val dependency: Boolean = false
}

private class CompilationUnit(
    override val name: String,
    override val sourceFolders: Set<SqlDelightSourceFolder>,
    override val outputDirectoryFile: File,
) : SqlDelightCompilationUnit

private class DatabaseProperties(
    override val packageName: String,
    override val className: String,
    override val compilationUnits: List<SqlDelightCompilationUnit>,
    override val deriveSchemaFromMigrations: Boolean,
    override val treatNullAsUnknownForEquality: Boolean,
    override val generateAsync: Boolean,
    override val expandSelectStar: Boolean,
    override val rootDirectory: File,
) : SqlDelightDatabaseProperties {
    override val dependencies: List<SqlDelightDatabaseName> = emptyList()
}
