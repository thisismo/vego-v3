package io.thisismo.vego.identity.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.thisismo.vego.common.server.auth.installAuth
import io.thisismo.vego.common.server.auth.requireAuth
import io.thisismo.vego.identity.common.IdentityApi
import io.thisismo.vego.identity.common.UserId
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.ktor.plugin.Koin
import kotlin.uuid.Uuid

fun Application.identityModule(integrationMode: Boolean = false) {
    if (integrationMode) {
        install(Koin)
        installAuth()
        install(Krpc) {
            serialization {
                json()
            }
        }
    }
    routing {
        requireAuth {
            route("/identity") {
                rpc {
                    val userId = call.principal<JWTPrincipal>()?.subject
                        ?: error("User not authenticated")
                    rpcConfig {
                        serialization { json() }
                    }
                    registerService<IdentityApi> {
                        IdentityService(UserId(Uuid.parse(userId)))
                    }
                }
            }
        }
    }
}