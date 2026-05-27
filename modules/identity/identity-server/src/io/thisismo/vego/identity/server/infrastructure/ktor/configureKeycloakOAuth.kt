package io.thisismo.vego.identity.server.infrastructure.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.OAuthAccessTokenResponse
import io.ktor.server.auth.OAuthServerSettings
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.oauth
import io.ktor.server.auth.principal
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.directorySessionStorage
import io.ktor.server.sessions.header
import io.thisismo.vego.common.server.auth.UserSession
import java.io.File

fun Application.configureKeycloakOAuth() {
    install(Sessions) {
        header<UserSession>("session", directorySessionStorage(File("build/.sessions")))
    }
    install(Authentication) {
        oauth("keycloak") {
            client = HttpClient(CIO)
            urlProvider = { "http://localhost:8080/callback" }
            settings = OAuthServerSettings.OAuth2ServerSettings(
                name = "keycloak",
                authorizeUrl = "http://localhost:9090/realms/vegoapp/protocol/openid-connect/auth",
                accessTokenUrl = "http://localhost:9090/realms/vegoapp/protocol/openid-connect/token",
                requestMethod = HttpMethod.Post,
                clientId = "vego-identity",
                clientSecret = "mysecret",
                defaultScopes = listOf("openid", "email", "profile", "offline_access")
            )
        }
    }
    routing {
        authenticate("keycloak") {
            get("/login") {
                // Redirects to 'authorizeUrl' automatically
            }

            get("/callback") {
                val currentPrincipal: OAuthAccessTokenResponse.OAuth2 =
                    call.principal() ?: throw IllegalArgumentException("Missing OAuth principal")
                println("Received OAuth callback with principal: $currentPrincipal")
                //call.sessions.set(UserSession(currentPrincipal.state, currentPrincipal.accessToken))
                call.respondRedirect("/home")
            }
        }
    }
}