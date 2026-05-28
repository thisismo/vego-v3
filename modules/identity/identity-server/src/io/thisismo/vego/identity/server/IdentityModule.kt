package io.thisismo.vego.identity.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.getAs
import io.ktor.server.request.requireQueryParameter
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.thisismo.vego.common.server.auth.SessionToken
import io.thisismo.vego.identity.common.UserId
import io.thisismo.vego.identity.common.client.IdentityApi
import io.thisismo.vego.identity.server.application.SessionService
import io.thisismo.vego.identity.server.application.UserIdentityApi
import io.thisismo.vego.identity.server.di.identityKoinModule
import io.thisismo.vego.identity.server.domain.UserRepository
import io.thisismo.vego.identity.server.infrastructure.oidc.KeycloakOAuthConfig
import io.thisismo.vego.identity.server.infrastructure.oidc.configureKeycloakOAuth
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.ktor.plugin.koinModules
import kotlin.uuid.Uuid

fun Application.identityModule(integrationMode: Boolean = false) {
    if (!integrationMode) {
        install(Koin)
        install(Krpc) {
            serialization {
                json()
            }
        }
    }

    val keycloakOAuthConfig: KeycloakOAuthConfig =
        ApplicationConfig("identity.conf").config("keycloak").getAs<KeycloakOAuthConfig>()

    koinModules(
        module {
            single { keycloakOAuthConfig }
        }, identityKoinModule
    )

    configureKeycloakOAuth(keycloakOAuthConfig)

    routing {
        val userRepository: UserRepository by inject()
        val sessionService: SessionService by inject()
        rpc("identity") {
            val token = SessionToken(call.requireQueryParameter("token"))
            rpcConfig {
                serialization { json() }
            }
            registerService<IdentityApi> {
                val userId = sessionService.getActiveSession(token).userId
                UserIdentityApi(userId, userRepository)
            }
        }
    }
}