package io.thisismo.vego.client.di

import io.thisismo.vego.client.auth.AuthService
import io.thisismo.vego.client.auth.IdentityServerConfig
import io.thisismo.vego.client.auth.OidcAuthService
import io.thisismo.vego.client.core.ClientCore
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.client.core.SessionTokenReceiver
import io.thisismo.vego.client.deeplink.DeeplinkHandler
import io.thisismo.vego.client.deeplink.DeeplinkRouter
import io.thisismo.vego.client.deeplink.IdentityCallbackDeeplinkHandler
import io.thisismo.vego.client.io.BackendReachability
import io.thisismo.vego.client.io.httpClient
import io.thisismo.vego.identity.common.client.IdentityApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.bind
import org.koin.dsl.includes
import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.tokenstore.TokenRefreshHandler

expect fun commonClientModule(): Module

@OptIn(ExperimentalOpenIdConnect::class)
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication {
    return startKoin {
        includes(config)
        modules(commonClientModule(), module {
            singleOf(::TokenRefreshHandler)
            singleOf(::ClientCore)
            singleOf(::SessionManager) bind SessionTokenReceiver::class
            singleOf(::OidcAuthService) bind AuthService::class
            single { IdentityServerConfig.Default }
            single { BackendReachability(get(), CoroutineScope(Dispatchers.Default + SupervisorJob())) }
            single<DeeplinkHandler> { IdentityCallbackDeeplinkHandler(get()) }
            single {
                DeeplinkRouter(
                    handlers = getAll<DeeplinkHandler>().distinct(),
                    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                )
            }
            single {
                val httpClient = httpClient(get(), get(), get(), get()) {
                    installKrpc {
                        serialization {
                            json()
                        }
                    }
                }
                val rpcClient = httpClient.rpc("ws://localhost:8080/identity") {
                    rpcConfig {
                        serialization {
                            json()
                        }
                    }
                }
                rpcClient.withService<IdentityApi>()
            }
        })
    }
}