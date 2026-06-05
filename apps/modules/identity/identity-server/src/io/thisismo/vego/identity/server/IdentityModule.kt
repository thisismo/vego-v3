package io.thisismo.vego.identity.server

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.thisismo.vego.common.server.auth.installAuth
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
        authenticate {
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