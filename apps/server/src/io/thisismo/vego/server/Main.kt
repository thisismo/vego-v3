package io.thisismo.vego.server

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.thisismo.vego.common.server.auth.installAuth
import io.thisismo.vego.identity.server.identityModule
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.ktor.plugin.Koin

fun main() {
    embeddedServer(CIO, port = 8081, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(Koin)
    install(CallLogging)
    install(Krpc) {
        serialization {
            json()
        }
    }
    installAuth()
    identityModule()
}