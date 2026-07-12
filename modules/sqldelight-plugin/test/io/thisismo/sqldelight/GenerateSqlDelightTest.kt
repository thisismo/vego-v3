package io.thisismo.sqldelight

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerateSqlDelightTest {

    private val moduleRoot = Files.createTempDirectory("sqldelight-plugin-module").toFile()
    private val outputDir = Files.createTempDirectory("sqldelight-plugin-output").toFile()

    @AfterTest
    fun deleteTempDirs() {
        moduleRoot.deleteRecursively()
        outputDir.deleteRecursively()
    }

    @Test
    fun `generates queries, model and database interface from a sq file`() {
        writeSource(
            "src/sqldelight/Player.sq",
            """
            CREATE TABLE player (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL
            );

            selectAll:
            SELECT *
            FROM player;

            insert:
            INSERT INTO player(name)
            VALUES (?);
            """,
        )

        generate()

        assertContains(generatedFile("sqldelight/Player.kt"), "public data class Player")
        assertContains(generatedFile("sqldelight/PlayerQueries.kt"), "public class PlayerQueries")
        assertContains(generatedFile("sqldelight/Database.kt"), "public interface Database : Transacter")
        assertTrue(outputDir.resolve("sqldelight/testmodule/DatabaseImpl.kt").isFile)
    }

    @Test
    fun `packageName and databaseClassName control the generated database`() {
        writeSource("src/com/example/db/Player.sq", tableWithSelectAll("player"))

        generate(packageName = "com.example.db", databaseClassName = "AppDatabase")

        val database = generatedFile("com/example/db/AppDatabase.kt")
        assertContains(database, "package com.example.db")
        assertContains(database, "public interface AppDatabase : Transacter")
        assertContains(generatedFile("com/example/db/PlayerQueries.kt"), "package com.example.db")
    }

    @Test
    fun `queries take their package from the directory of the sq file`() {
        writeSource(
            "src/db/tables/Player.sq",
            """
            CREATE TABLE player (
              id INTEGER PRIMARY KEY
            );
            """,
        )

        generate(packageName = "db")

        assertContains(generatedFile("db/tables/Player.kt"), "package db.tables")
        assertContains(generatedFile("db/Database.kt"), "package db")
    }

    @Test
    fun `platform-qualified source roots are compiled together`() {
        writeSource("src/sqldelight/Player.sq", tableWithSelectAll("player"))
        writeSource("src@android/sqldelight/Setting.sq", tableWithSelectAll("setting"))

        generate()

        assertTrue(outputDir.resolve("sqldelight/PlayerQueries.kt").isFile)
        assertTrue(outputDir.resolve("sqldelight/SettingQueries.kt").isFile)
    }

    @Test
    fun `outputs of deleted sq files do not survive a rerun`() {
        val playerSq = writeSource("src/sqldelight/Player.sq", tableWithSelectAll("player"))
        generate()
        assertTrue(outputDir.resolve("sqldelight/PlayerQueries.kt").isFile)

        playerSq.delete()
        writeSource("src/sqldelight/User.sq", tableWithSelectAll("user"))
        generate()

        assertFalse(outputDir.resolve("sqldelight/PlayerQueries.kt").exists())
        assertTrue(outputDir.resolve("sqldelight/UserQueries.kt").isFile)
    }

    @Test
    fun `generateAsync produces a suspending database`() {
        writeSource(
            "src/sqldelight/Player.sq",
            """
            CREATE TABLE player (
              id INTEGER PRIMARY KEY
            );
            """,
        )

        generate(generateAsync = true)

        assertContains(generatedFile("sqldelight/Database.kt"), "SuspendingTransacter")
    }

    @Test
    fun `fails with file location and sql excerpt on invalid sql`() {
        writeSource(
            "src/sqldelight/Player.sq",
            """
            CREATE TABLE player (
              id INTEGER PRIMARY KEY
            );

            selectAll:
            SELECT missing_column
            FROM player;
            """,
        )

        val failure = assertFailsWith<IllegalStateException> { generate() }

        val message = failure.message.orEmpty()
        assertContains(message, "Player.sq")
        assertContains(message, "missing_column")
    }

    @Test
    fun `newer sqlite syntax requires a matching dialect`() {
        writeSource(
            "src/sqldelight/Player.sq",
            """
            CREATE TABLE player (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL
            );

            deleteAndReturn:
            DELETE FROM player
            WHERE id = ?
            RETURNING name;
            """,
        )

        assertFailsWith<IllegalStateException> { generate(dialect = SqlDialect.SQLITE_3_18) }

        outputDir.deleteRecursively()
        generate(dialect = SqlDialect.SQLITE_3_38)
        assertTrue(outputDir.resolve("sqldelight/PlayerQueries.kt").isFile)
    }

    @Test
    fun `verifyMigrations type-checks sqm files`() {
        writeSource(
            "src/sqldelight/Player.sq",
            """
            CREATE TABLE player (
              id INTEGER PRIMARY KEY
            );
            """,
        )
        writeSource("src/sqldelight/migrations/1.sqm", "ALTER TABLE no_such_table ADD COLUMN broken TEXT;")

        generate(verifyMigrations = false)

        outputDir.deleteRecursively()
        val failure = assertFailsWith<IllegalStateException> { generate(verifyMigrations = true) }
        assertContains(failure.message.orEmpty(), "1.sqm")
    }

    @Test
    fun `deriveSchemaFromMigrations builds the schema from sqm files`() {
        writeSource(
            "src/sqldelight/1.sqm",
            """
            CREATE TABLE user (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL
            );
            """,
        )
        writeSource(
            "src/sqldelight/User.sq",
            """
            selectAll:
            SELECT *
            FROM user;
            """,
        )

        generate(deriveSchemaFromMigrations = true)

        assertContains(generatedFile("sqldelight/User.kt"), "public data class User")
        assertTrue(outputDir.resolve("sqldelight/UserQueries.kt").isFile)
    }

    @Test
    fun `module without sql sources is skipped without failing`() {
        writeSource("src/io/example/Irrelevant.kt", "package io.example\n\nval irrelevant = Unit")

        generate()

        assertEquals(emptyList(), outputDir.walkTopDown().filter(File::isFile).toList())
    }

    @Test
    fun `every dialect can be instantiated`() {
        SqlDialect.entries.forEach { dialect -> dialect.newInstance() }
    }

    @Test
    fun `database class name colliding with the implementation package is rejected`() {
        writeSource(
            "src/sqldelight/Player.sq",
            """
            CREATE TABLE player (
              id INTEGER PRIMARY KEY
            );
            """,
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            generate(moduleName = "Database", databaseClassName = "Database")
        }
        assertContains(failure.message.orEmpty(), "databaseClassName")
    }

    @Test
    fun `invalid package names are rejected before compilation`() {
        val failure = assertFailsWith<IllegalArgumentException> { generate(packageName = "com.example.1db") }
        assertContains(failure.message.orEmpty(), "packageName")
    }

    private fun generate(
        moduleName: String = "test-module",
        packageName: String = "sqldelight",
        databaseClassName: String = "Database",
        dialect: SqlDialect = SqlDialect.SQLITE_3_18,
        generateAsync: Boolean = false,
        deriveSchemaFromMigrations: Boolean = false,
        verifyMigrations: Boolean = false,
    ) {
        generateSqlDelight(
            moduleRootDir = moduleRoot.toPath(),
            outputDirectory = outputDir.toPath(),
            moduleName = moduleName,
            packageName = packageName,
            databaseClassName = databaseClassName,
            dialect = dialect,
            generateAsync = generateAsync,
            deriveSchemaFromMigrations = deriveSchemaFromMigrations,
            verifyMigrations = verifyMigrations,
        )
    }

    /** A table definition plus a named query, so that a `<Table>Queries.kt` file is generated. */
    private fun tableWithSelectAll(table: String): String =
        """
        CREATE TABLE $table (
          id INTEGER PRIMARY KEY,
          name TEXT NOT NULL
        );

        selectAll:
        SELECT *
        FROM $table;
        """

    private fun writeSource(relativePath: String, content: String): File =
        moduleRoot.resolve(relativePath).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent())
        }

    private fun generatedFile(relativePath: String): String {
        val file = outputDir.resolve(relativePath)
        assertTrue(file.isFile, "Expected $file to be generated. Present files:\n" + presentFiles())
        return file.readText()
    }

    private fun presentFiles(): String =
        outputDir.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.relativeTo(outputDir).path }
            .ifEmpty { "(none)" }
}
