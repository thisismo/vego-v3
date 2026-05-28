package io.thisismo.vego.identity.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.getAs
import io.ktor.server.routing.routing
import io.thisismo.vego.identity.server.di.identityKoinModule
import io.thisismo.vego.identity.server.infrastructure.oidc.KeycloakOAuthConfig
import io.thisismo.vego.identity.server.infrastructure.oidc.configureKeycloakOAuth
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.ktor.plugin.koinModules

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

    }
}