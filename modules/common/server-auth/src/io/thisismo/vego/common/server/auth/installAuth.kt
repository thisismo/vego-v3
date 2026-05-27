package io.thisismo.vego.common.server.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.thisismo.vego.identity.common.UserId
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

const val jwksUri = "http://localhost:8080/realms/vegoapp/protocol/openid-connect/certs"
val trustedIssuers = listOf(
    "http://bwpm-l6p73904tf.local:8080/realms/vegoapp",
    "http://moritzs-macbook-pro.local:8080/realms/vegoapp",
    "http://localhost:8080/realms/vegoapp"
)

val keycloakJwkProvider: JwkProvider =
    JwkProviderBuilder(URI(jwksUri).toURL()).cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()


fun Application.installAuth() {
    install(Authentication) {
        jwt("jwt-auth") {
            verifier(keycloakJwkProvider) {
                withIssuer(*trustedIssuers.toTypedArray())
            }
            validate { credential ->
                if (credential.payload.getClaim("sub").asString() != "") {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
}