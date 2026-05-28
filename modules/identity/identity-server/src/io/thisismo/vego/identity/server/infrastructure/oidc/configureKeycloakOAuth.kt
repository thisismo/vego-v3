package io.thisismo.vego.identity.server.infrastructure.oidc

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
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
import io.thisismo.vego.identity.server.application.SessionService
import io.thisismo.vego.identity.server.domain.OAuthTokens
import org.koin.ktor.ext.inject
import java.io.File

fun Application.configureKeycloakOAuth(config: KeycloakOAuthConfig) {
    install(Authentication) {
        oauth("keycloak") {
            client = HttpClient(CIO)
            urlProvider = { "http://localhost:8080/callback" }
            settings = OAuthServerSettings.OAuth2ServerSettings(
                name = "keycloak",
                authorizeUrl = config.authorizeUrl,
                accessTokenUrl = config.accessTokenUrl,
                requestMethod = HttpMethod.Post,
                clientId = config.clientId,
                clientSecret = config.clientSecret,
                defaultScopes = listOf("openid", "email", "profile", "offline_access")
            )
        }
    }
    routing {
        val sessionService: SessionService by inject()
        authenticate("keycloak") {
            get("/login") { /* THIS NEEDS TO STAY EMPTY */ }

            get("/callback") {
                val currentPrincipal: OAuthAccessTokenResponse.OAuth2 =
                    call.principal() ?: throw IllegalArgumentException("Missing OAuth principal")
                println("Received OAuth callback with principal: $currentPrincipal")
                val session = sessionService.initiateSession(OAuthTokens(
                    identityToken = currentPrincipal.extraParameters["id_token"] ?: throw IllegalArgumentException("Missing id_token in OAuth principal"),
                    accessToken = currentPrincipal.accessToken,
                    refreshToken = currentPrincipal.extraParameters["refresh_token"] ?: throw IllegalArgumentException("Missing refresh_token in OAuth principal")
                ))
                log.debug("Initiated session with token: ${session.token}")
                call.respondRedirect("vego-v3://identity/callback?sessionToken=${session.token}")
            }
        }
    }
}