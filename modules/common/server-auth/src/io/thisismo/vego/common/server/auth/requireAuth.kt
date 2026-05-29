package io.thisismo.vego.common.server.auth

import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.thisismo.vego.identity.common.UserId

fun Route.requireAuth(block: Route.() -> Unit) {
    authenticate("jwt-auth") {
        block()
    }
}

data class UserPrincipal(
    val userId: UserId
)