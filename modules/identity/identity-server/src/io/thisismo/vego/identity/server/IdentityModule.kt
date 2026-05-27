package io.thisismo.vego.identity.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.OAuthAccessTokenResponse
import io.ktor.server.auth.OAuthServerSettings
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.oauth
import io.ktor.server.auth.principal
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.directorySessionStorage
import io.ktor.server.sessions.header
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.thisismo.vego.common.server.auth.UserSession
import io.thisismo.vego.common.server.auth.requireAuth
import io.thisismo.vego.identity.server.infrastructure.ktor.configureKeycloakOAuth
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.ktor.plugin.Koin
import org.koin.ktor.plugin.koinModules
import java.io.File

fun Application.identityModule(integrationMode: Boolean = false) {
    if (!integrationMode) {
        install(Koin)
        install(Krpc) {
            serialization {
                json()
            }
        }
    }
    koinModules()
    configureKeycloakOAuth()

    routing {
        
    }
}