package io.thisismo.vego.common.server.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Routing
import io.thisismo.vego.identity.common.UserId

fun Routing.requireAuth(block: Routing.() -> Unit) {
    authenticate("jwt-auth") {
        block()
    }
}

val ApplicationCall.userId: UserId
    get() = principal<UserId>() ?: error("User not authenticated")