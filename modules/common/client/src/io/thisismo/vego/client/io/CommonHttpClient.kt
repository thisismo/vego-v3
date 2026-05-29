package io.thisismo.vego.client.io

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.thisismo.vego.client.auth.oidcClient
import io.thisismo.vego.client.core.SessionManager
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.serialization.json.json
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.ktor.oidcBearer
import org.publicvalue.multiplatform.oidc.tokenstore.TokenRefreshHandler
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import co.touchlab.kermit.Logger as KermitLogger

class HttpClientDependencies @OptIn(ExperimentalOpenIdConnect::class) constructor(
    val tokenStore: TokenStore,
    val refreshHandler: TokenRefreshHandler,
    val sessionManager: SessionManager,
    val backendReachability: BackendReachability,
)

@OptIn(ExperimentalOpenIdConnect::class)
fun HttpClientConfig<*>.setUpMiddleWare(deps: HttpClientDependencies) {
    install(Logging) {
        logger = object : Logger {
            private val kermit = KermitLogger.withTag("Ktor")
            override fun log(message: String) {
                kermit.d { message }
            }
        }
        level = LogLevel.ALL
    }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 5)
        exponentialDelay()
        retryIf { _, _ -> !deps.sessionManager.offlineMode }
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 5_000
    }
    engine {
        expectSuccess = true
    }
    install(Auth) {
        oidcBearer(
            tokenStore = deps.tokenStore,
            refreshHandler = deps.refreshHandler,
            client = oidcClient,
        )
    }
    installKrpc {
        serialization { json() }
    }
    HttpResponseValidator {
        validateResponse { response ->
            when (val status = response.status) {
                HttpStatusCode.Unauthorized -> deps.sessionManager.logout()
                else -> when (status.value) {
                    in 200..299 -> deps.backendReachability.reportSuccess()
                    in 500..599 -> deps.backendReachability.reportFailure()
                }
            }
        }
    }
}