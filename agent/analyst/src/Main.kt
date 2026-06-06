package io.thisismo.vego.agent

import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.utils.time.KoogClock
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

private val logger = KotlinLogging.logger {}

/**
 * Entry point of the business-analysis / architecture agent.
 *
 * Following the Koog `acp-agent` example, the agent speaks the Agent Client Protocol over stdio:
 * the IDE spawns this process and exchanges newline-delimited JSON-RPC on stdin/stdout. That
 * [Protocol] host is the local agent server the workflows describe; each ACP session gets its own
 * isolated Koog state graph via [KoogAnalystSupport]. Logs go to stderr (see logback.xml) so they
 * never corrupt the protocol stream on stdout.
 */
suspend fun main() = coroutineScope {
    logger.info { "Starting business-analysis / architecture ACP agent" }

    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("The API key is not set.")

    val agentTransport = StdioTransport(
        parentScope = this,
        ioDispatcher = Dispatchers.IO,
        input = BufferedInputStream(System.`in`).asSource().buffered(),
        output = BufferedOutputStream(System.out).asSink().buffered(),
        name = "agent",
    )
    val promptExecutor = simpleOpenAIExecutor(apiKey)
    // Per-stage models: defaults live in AnalystModelConfig, overridable via ANALYST_MODEL_<STAGE> env vars.
    val models = AnalystModelConfig.fromEnvironment()

    agentTransport.use { agentTransport ->
        val agentJob = launch {
            val agentProtocol = Protocol(this, agentTransport)

            Agent(
                agentProtocol,
                KoogAnalystSupport(
                    protocol = agentProtocol,
                    promptExecutor = promptExecutor,
                    clock = KoogClock.System,
                    models = models,
                ),
            )

            logger.info { "Agent initialized, starting protocol" }
            agentProtocol.start()
        }

        agentJob.join()
        logger.info { "Agent job completed" }
    }
}
