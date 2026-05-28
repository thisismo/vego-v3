package io.thisismo.vego.server

import co.touchlab.kermit.Logger
import io.ktor.http.DEFAULT_PORT
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.thisismo.vego.identity.server.identityModule
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.ktor.plugin.Koin

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(Koin)
    install(Krpc) {
        serialization {
            json()
        }
    }
    identityModule(true)
    Logger.i("Started Vego server on port 8080")
}