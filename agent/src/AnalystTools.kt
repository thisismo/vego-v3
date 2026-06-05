package io.thisismo.vego.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * Local system tools exposed to the agent. All paths are absolute — the workspace root is given to
 * the model in the system prompt so it can resolve them. These back the TechnicalDesign (write
 * ADRs / OpenAPI / C4), Validation (lint/build via shell) and Teardown (git commit via shell)
 * nodes of the state graph.
 */

@Tool(customName = "list_directory")
@LLMDescription("Lists entries under an absolute directory path, recursing up to the given depth.")
fun listDirectory(
    @LLMDescription("Absolute path of the directory to list") absolutePath: String,
    @LLMDescription("How many levels deep to recurse (>= 1)") depth: Int = 1,
): List<String> {
    require(depth > 0) { "Depth must be at least 1 (got $depth)" }

    val rootPath = Path(absolutePath)
    require(rootPath.exists()) { "Path does not exist: $absolutePath" }
    require(rootPath.isDirectory()) { "Path is not a directory: $absolutePath" }

    val result = mutableListOf<String>()

    fun walk(current: java.nio.file.Path, currentDepth: Int) {
        if (currentDepth > depth) return
        current.listDirectoryEntries().forEach { entry ->
            result += entry.relativeTo(rootPath).pathString
            if (entry.isDirectory() && currentDepth < depth) {
                walk(entry, currentDepth + 1)
            }
        }
    }

    walk(rootPath, 1)
    return result.sorted()
}

@Tool(customName = "read_file")
@LLMDescription("Reads and returns the full text content of an absolute file path.")
fun readFile(
    @LLMDescription("Absolute path of the file to read") absolutePath: String,
): String {
    val path = Path(absolutePath)
    require(path.exists()) { "Path does not exist: $absolutePath" }
    require(path.isRegularFile()) { "Path is not a regular file: $absolutePath" }
    return path.readText()
}

@Tool(customName = "write_file")
@LLMDescription("Writes text to an absolute file path, creating parent directories and overwriting any existing file.")
fun writeFile(
    @LLMDescription("Absolute path of the file to write") absolutePath: String,
    @LLMDescription("The full text content to write") content: String,
): String {
    val path = Path(absolutePath)
    path.parent?.createDirectories()
    path.writeText(content)
    return "Wrote ${content.length} characters to $absolutePath"
}

@Tool(customName = "edit_file")
@LLMDescription("Replaces the first occurrence of an exact substring in a file. Creates the file if missing.")
fun editFile(
    @LLMDescription("Absolute path of the file to edit") absolutePath: String,
    @LLMDescription("The exact text to find") original: String,
    @LLMDescription("The text to replace it with") replacement: String,
): String {
    val path = Path(absolutePath)
    if (!path.exists()) {
        path.parent?.createDirectories()
        path.createFile()
    }
    require(path.isRegularFile()) { "Path is not a regular file: $absolutePath" }

    val content = path.readText()
    require(original in content) { "Original text not found in file: $absolutePath" }
    path.writeText(content.replace(original, replacement))
    return "Edited $absolutePath"
}

@Tool(customName = "run_shell_command")
@LLMDescription(
    "Runs a shell command in the given working directory and returns its exit code together with " +
        "combined stdout/stderr. Use this for validation (linters, static-site/build checks) and for " +
        "git staging/commit during teardown."
)
fun runShellCommand(
    @LLMDescription("Absolute path of the working directory to run the command in") workingDirectory: String,
    @LLMDescription("The shell command line to execute, e.g. 'git add -A && git commit -m \"docs: ADRs\"'") command: String,
): String {
    val workdir = File(workingDirectory)
    require(workdir.isDirectory) { "Working directory does not exist: $workingDirectory" }

    val process = ProcessBuilder("/bin/sh", "-c", command)
        .directory(workdir)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val finished = process.waitFor(120, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return "exit=timeout (command exceeded 120s)\n$output"
    }
    return "exit=${process.exitValue()}\n$output"
}

/**
 * Workflow 1, Hydration node — a lightweight, file-based stand-in for "pulling contextual markdown
 * from the local vector store". It scans the workspace for existing markdown documentation and
 * returns a bounded set of excerpts that ground the business analysis in the project's real domain
 * boundaries. Swapping this for a true embeddings-backed retriever is a drop-in change.
 */
fun hydrateDomainContext(workspaceRoot: String, maxFiles: Int = 12, maxCharsPerFile: Int = 2_000): String {
    val root = Path(workspaceRoot)
    if (!root.exists() || !root.isDirectory()) return "No workspace context available."

    val markdown = root.toFile()
        .walkTopDown()
        .onEnter { dir -> dir.name !in setOf(".git", "build", ".gradle", "node_modules", ".idea") }
        .filter { it.isFile && it.extension.lowercase() in setOf("md", "markdown") }
        .take(maxFiles)
        .toList()

    if (markdown.isEmpty()) return "No existing markdown documentation found in the workspace."

    return buildString {
        appendLine("Existing project documentation (retrieved from the local workspace):")
        markdown.forEach { file ->
            val rel = file.relativeTo(root.toFile()).path
            appendLine()
            appendLine("--- $rel ---")
            appendLine(file.readText().take(maxCharsPerFile))
        }
    }
}
