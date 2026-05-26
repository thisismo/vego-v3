package io.thisismo.vego.identity.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.thisismo.vego.common.server.auth.installAuth
import io.thisismo.vego.common.server.auth.requireAuth
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import org.koin.ktor.plugin.Koin

fun Application.identityModule(integrationMode: Boolean = false) {
    if(integrationMode) {
        install(Koin)
        installAuth()
        install(Krpc) {
            serialization {
                json()
            }
        }
    }
    routing {
        rpc("/api/userdata") {
            val principal = call.principal<UserIdPrincipal>()
                ?: throw IllegalArgumentException("Missing principal")

            rpcConfig {
                serialization { json() }
            }

            // Inject the ID directly into the constructor
            registerService<UserDataService> {
                UserDataServiceImpl(scopedUserId = principal.name, repository = DataRepository())
            }
        }
        requireAuth {
        }
    }
}