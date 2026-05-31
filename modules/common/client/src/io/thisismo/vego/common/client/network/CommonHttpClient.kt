package io.thisismo.vego.common.client.network

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.callid.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.thisismo.vego.common.client.EndSessionHandler
import io.thisismo.vego.common.client.auth.oidcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.serialization.json.json
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.ktor.oidcBearer
import org.publicvalue.multiplatform.oidc.tokenstore.TokenRefreshHandler
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import kotlin.uuid.Uuid

class HttpClientDependencies @OptIn(ExperimentalOpenIdConnect::class) constructor(
    val tokenStore: TokenStore,
    val refreshHandler: TokenRefreshHandler,
    val networkMonitor: NetworkMonitor,
    val backendReachability: BackendReachability,
    val endSessionHandler: EndSessionHandler
)

@OptIn(ExperimentalOpenIdConnect::class)
fun HttpClientConfig<*>.setUpMiddleWare(deps: HttpClientDependencies) {
    install(CallId) {
        generate {
            val requestId = Uuid.generateV7()
            requestId.toString()
        }
        addToHeader(HttpHeaders.XRequestId)
    }
    install(Logging) {
        logger = Logger.SIMPLE
        level = LogLevel.ALL
    }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 5)
        exponentialDelay()
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
                HttpStatusCode.Unauthorized -> deps.endSessionHandler.endSession()
                else -> when (status.value) {
                    in 200..299 -> deps.backendReachability.reportSuccess()
                    in 500..599 -> deps.backendReachability.reportFailure()
                }
            }
        }
    }
}