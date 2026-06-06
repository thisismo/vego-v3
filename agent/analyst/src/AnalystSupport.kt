package io.thisismo.vego.agent

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.utils.time.KoogClock
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.uuid.Uuid

/**
 * Wires Koog sessions into the ACP agent lifecycle: advertises capabilities on initialize and mints
 * a fresh, isolated [KoogAnalystSession] per ACP session.
 */
class KoogAnalystSupport(
    private val promptExecutor: PromptExecutor,
    private val protocol: Protocol,
    private val clock: KoogClock,
    private val models: AnalystModelConfig,
) : AgentSupport {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        logger.info { "Initializing analyst agent for client: ${clientInfo.capabilities}" }
        return AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(
                loadSession = false,
                promptCapabilities = PromptCapabilities(
                    audio = false,
                    image = false,
                    embeddedContext = true,
                ),
            ),
            authMethods = emptyList(),
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val sessionId = SessionId(Uuid.random().toString())
        logger.info { "Creating analyst session $sessionId in ${sessionParameters.cwd}" }
        return KoogAnalystSession(
            sessionId = sessionId,
            promptExecutor = promptExecutor,
            protocol = protocol,
            clock = clock,
            workspaceRoot = sessionParameters.cwd,
            models = models,
        )
    }
}
