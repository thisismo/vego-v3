package io.thisismo.vego.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalystToolsTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    /** Creates a temp directory holding a single `doc.md` with [content]; returns the directory path. */
    private fun docsDir(content: String): String {
        val dir = Files.createTempDirectory("analyst-tools-test").also { tempDirs.add(it) }
        dir.resolve("doc.md").writeText(content)
        return dir.toString()
    }

    private fun model(vararg contexts: String) = DomainModel(
        summary = "test model",
        ubiquitousLanguage = emptyList(),
        boundedContexts = contexts.map { BoundedContext(it, "purpose", emptyList(), emptyList()) },
        outOfScope = emptyList(),
    )

    // ---- Mermaid structural validation ----

    @Test
    fun wellFormedC4DiagramPasses() {
        val dir = docsDir(
            """
            # Context

            ```mermaid
            C4Context
              title System Context
              Person(user, "User", "A person (the customer)")
              System(ordering, "Ordering", "Takes orders")
              Rel(user, ordering, "places orders")
            ```
            """.trimIndent()
        )
        val (_, findings) = validateMarkdownDocs(listOf(dir))
        assertEquals(emptyList(), findings)
    }

    @Test
    fun c4DiagramWithoutElementsIsFlagged() {
        val dir = docsDir(
            """
            ```mermaid
            C4Container
              title Containers only in name
            ```
            """.trimIndent()
        )
        val (_, findings) = validateMarkdownDocs(listOf(dir))
        assertTrue(findings.any { it.message.contains("no C4 elements") })
    }

    @Test
    fun unbalancedDelimiterIsFlagged() {
        val dir = docsDir(
            """
            ```mermaid
            flowchart TD
              A[Start --> B[End]
            ```
            """.trimIndent()
        )
        val (_, findings) = validateMarkdownDocs(listOf(dir))
        assertTrue(findings.any { it.message.contains("unbalanced") })
    }

    @Test
    fun unbalancedPunctuationInsideQuotedLabelsIsIgnored() {
        val dir = docsDir(
            """
            ```mermaid
            C4Context
              Person(user, "A user :) with an open ( paren")
              System(sys, "System")
              Rel(user, sys, "uses")
            ```
            """.trimIndent()
        )
        val (_, findings) = validateMarkdownDocs(listOf(dir))
        assertEquals(emptyList(), findings)
    }

    // ---- Domain-model cross-check ----

    @Test
    fun crossCheckFlagsContextMissingFromDiagrams() {
        val dir = docsDir(
            """
            ```mermaid
            C4Context
              System(orderManagement, "Order Management")
            ```
            """.trimIndent()
        )
        val findings = crossCheckC4AgainstDomainModel(dir, model("Order Management", "Billing"))
        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("'Billing'"))
    }

    @Test
    fun crossCheckMatchesContextNamesLeniently() {
        // "Order Management" should match the conventional space-stripped C4 alias "orderManagement".
        val dir = docsDir(
            """
            ```mermaid
            C4Container
              Container(orderManagement, "orders")
            ```
            """.trimIndent()
        )
        assertEquals(emptyList(), crossCheckC4AgainstDomainModel(dir, model("Order Management")))
    }

    @Test
    fun crossCheckIsSilentWhenNoDiagramsExistYet() {
        val dir = docsDir("# No diagrams here yet")
        assertEquals(emptyList(), crossCheckC4AgainstDomainModel(dir, model("Ordering")))
    }
}
