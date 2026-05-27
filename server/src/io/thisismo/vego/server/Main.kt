package io.thisismo.vego.server

import io.ktor.http.DEFAULT_PORT
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.thisismo.vego.common.server.auth.installAuth
import io.thisismo.vego.identity.server.identityModule
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.ktor.plugin.Koin

fun main() {
    embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(Koin)
    install(Krpc) {
        serialization {
            json()
        }
    }
    installAuth()
    identityModule()
}