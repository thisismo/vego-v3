package io.thisismo.vego.client.io

import co.touchlab.kermit.Logger as KermitLogger
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpStatusCode
import io.thisismo.vego.client.auth.OidcAuthService
import io.thisismo.vego.client.auth.oidcClient
import io.thisismo.vego.client.core.SessionManager
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.ktor.oidcBearer
import org.publicvalue.multiplatform.oidc.tokenstore.TokenRefreshHandler
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

@OptIn(ExperimentalOpenIdConnect::class)
fun HttpClientConfig<*>.setUpMiddleWare(tokenStore: TokenStore, refreshHandler: TokenRefreshHandler, sessionManager: SessionManager, backendReachability: BackendReachability) {
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

        retryIf { response, _ ->
            !sessionManager.offlineMode
        }
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
            tokenStore = tokenStore,
            refreshHandler = refreshHandler,
            client = oidcClient,
        )
    }
    HttpResponseValidator {
        validateResponse { response ->
            when(val status = response.status) {
                HttpStatusCode.Unauthorized -> sessionManager.logout()
                else -> {
                    when(status.value) {
                        in 200..299 -> backendReachability.reportSuccess()
                        in 500..599 ->backendReachability.reportFailure()
                    }
                }
            }
        }

    }
}

@OptIn(ExperimentalOpenIdConnect::class)
fun httpClient(tokenStore: TokenStore, refreshHandler: TokenRefreshHandler, sessionManager: SessionManager, backendReachability: BackendReachability, extraConfig: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient {
    setUpMiddleWare(tokenStore, refreshHandler, sessionManager, backendReachability)
    extraConfig()
}

class ResponseInterceptorPluginConfig {
    var backendReachability: BackendReachability? = null
}