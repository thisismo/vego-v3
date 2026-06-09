package io.thisismo.vego.agent

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.feature.handler.agent.AgentExecutionFailedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallFailedContext
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.channels.ProducerScope

/**
 * The single, shared visual vocabulary for everything the analyst streams to the IDE. Keeping the
 * glyphs in one place keeps the agent's "voice" consistent across the dashboards ([ConsensusRenderers],
 * [AnalystRenderers]) and the live progress lines, and makes a future plain-text mode a one-file change.
 */
internal object Ui {
    const val CONVERGED = "🟢"
    const val DEADLOCK = "⛔"
    const val APPROVE = "✅"
    const val CONCERNS = "🟡"
    const val BLOCK = "⛔"
    const val WARN = "⚠️"
    const val DONE = "✅"
    const val PROPOSAL = "💡"

    // Live progress markers for the in-between narration.
    const val HYDRATE = "🔍"
    const val MODEL = "🧠"
    const val POOL = "🗳️"
    const val DEBATE = "🔄"
    const val DESIGN = "📐"
    const val VALIDATE = "🔧"
    const val MEMORY = "🧾"

    // Tool-call markers.
    const val WROTE = "📝"
    const val EDITED = "✏️"
    const val READ = "📖"
    const val LISTED = "📂"
    const val SHELL = "⚙️"
    const val LINT = "🔍"
    const val DELETE = "🗑️"
}

/** How chatty the live progress narration is. Overridable via `ANALYST_PROGRESS_VERBOSITY`. */
internal enum class Verbosity {
    /** Only the curated dashboards and phase headers — no per-tool lines. */
    QUIET,

    /** Dashboards, headers, and one concise line per *mutating* tool call (write/edit/delete/lint/shell). */
    NORMAL,

    /** Everything NORMAL shows, plus read/list calls and a fallback line for unknown tools. */
    VERBOSE;

    companion object {
        fun fromEnvironment(getenv: (String) -> String? = System::getenv): Verbosity =
            getenv("ANALYST_PROGRESS_VERBOSITY")?.trim()?.uppercase()
                ?.let { name -> entries.firstOrNull { it.name == name } }
                ?: NORMAL
    }
}

/**
 * The one place the session talks to the IDE.
 *
 * It owns the ACP [ProducerScope] and turns the two kinds of output into events:
 *  - **Curated** dashboards / headers, emitted explicitly by the phase machine via [message] / [step].
 *  - **Live** tool progress, emitted from the agent's [ai.koog.agents.features.eventHandler.feature.EventHandler]
 *    callbacks — one terse line per meaningful tool call instead of Koog's raw, verbose default
 *    notifications (which we switch off; see `KoogAnalystSession.buildAgent`).
 *
 * It also re-emits the single piece of ACP default-notification behaviour we still want — ending the
 * turn ([onAgentCompleted] / [onAgentFailed]) — so suppressing default notifications never strands a turn.
 */
internal class ProgressReporter(
    val producer: ProducerScope<Event>,
    private val workspaceRoot: String,
    private val verbosity: Verbosity = Verbosity.fromEnvironment(),
) {
    /** Emit one curated Markdown chunk — the dashboards and closing messages. */
    suspend fun message(markdown: String) {
        producer.send(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(markdown))))
    }

    /** A short, italicised "what I'm doing now" line for the gaps between dashboards. */
    suspend fun step(text: String) = message("_${text}_")

    /** EventHandler: one concise line per completed tool call (suppressed/expanded per [verbosity]). */
    suspend fun onToolCompleted(ctx: ToolCallCompletedContext) {
        summarize(ctx.toolName, ctx.toolArgs)?.let { message(it) }
    }

    /** EventHandler: surface tool failures so a silent self-heal loop is never a black box. */
    suspend fun onToolFailed(ctx: ToolCallFailedContext) {
        if (verbosity == Verbosity.QUIET) return
        val target = pathArg(ctx.toolArgs)?.let { " `${rel(it)}`" } ?: ""
        message("${Ui.WARN} `${ctx.toolName}`$target failed: ${ctx.message}")
    }

    /** EventHandler: replicate the END_TURN we gave up by disabling default notifications. */
    suspend fun onAgentCompleted() {
        producer.send(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
    }

    /** EventHandler: map an unrecovered agent failure to the matching ACP stop reason. */
    suspend fun onAgentFailed(ctx: AgentExecutionFailedContext) {
        val reason = if (ctx.error is AIAgentMaxNumberOfIterationsReachedException) {
            StopReason.MAX_TURN_REQUESTS
        } else {
            StopReason.REFUSAL
        }
        producer.send(Event.PromptResponseEvent(PromptResponse(stopReason = reason)))
    }

    /** Render a tool call as a single line, or null to suppress it at the current verbosity. */
    private fun summarize(toolName: String, args: JSONObject): String? {
        if (verbosity == Verbosity.QUIET) return null
        val name = toolName.lowercase()
        val target = pathArg(args)?.let { rel(it) }
        return when {
            "write" in name -> "${Ui.WROTE} Wrote `${target ?: "file"}`"
            "edit" in name -> "${Ui.EDITED} Edited `${target ?: "file"}`"
            "delete" in name -> "${Ui.DELETE} Deleted `${target ?: "file"}`"
            "lint" in name -> "${Ui.LINT} Linted `${target ?: "docs"}`"
            "shell" in name || "command" in name -> {
                val cmd = strArg(args, "command")?.let { truncate(it, 80) } ?: "shell command"
                "${Ui.SHELL} Ran `$cmd`"
            }
            verbosity != Verbosity.VERBOSE -> null // reads/lists/unknown are noise at NORMAL
            "read" in name -> "${Ui.READ} Read `${target ?: "file"}`"
            "list" in name || "directory" in name -> "${Ui.LISTED} Listed `${target ?: "dir"}`"
            else -> "🔧 `$toolName`"
        }
    }

    /** Best-effort extraction of the file/dir a tool acted on, across the built-in tools' arg names. */
    private fun pathArg(args: JSONObject): String? =
        strArg(args, "path")
            ?: strArg(args, "absolutePath")
            ?: strArg(args, "absoluteDirectory")
            ?: strArg(args, "filePath")
            ?: strArg(args, "file")

    private fun strArg(args: JSONObject, key: String): String? =
        (args.entries[key] as? JSONPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun rel(absolute: String): String = absolute.removePrefix("$workspaceRoot/")

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}
