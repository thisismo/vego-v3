package io.thisismo.vego.client.di

import io.ktor.client.*
import io.thisismo.vego.client.auth.AuthService
import io.thisismo.vego.client.auth.OidcAuthService
import io.thisismo.vego.client.core.ClientCore
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.client.io.BackendReachability
import io.thisismo.vego.client.io.HttpClientDependencies
import io.thisismo.vego.client.io.RpcConnectionManager
import io.thisismo.vego.client.io.setUpMiddleWare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.logger.KOIN_TAG
import org.koin.core.logger.Level
import org.koin.core.logger.MESSAGE
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.bind
import org.koin.dsl.includes
import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.tokenstore.TokenRefreshHandler
import co.touchlab.kermit.Logger as KermitLogger
import org.koin.core.logger.Logger as KoinLogger

expect fun commonClientModule(): Module

private class KermitKoinLogger(level: Level = Level.DEBUG) : KoinLogger(level) {
    private val kermit = KermitLogger.withTag(KOIN_TAG)
    override fun display(level: Level, msg: MESSAGE) {
        when (level) {
            Level.DEBUG -> kermit.d { msg }
            Level.INFO -> kermit.i { msg }
            Level.WARNING -> kermit.w { msg }
            Level.ERROR -> kermit.e { msg }
            Level.NONE -> {}
        }
    }
}

@OptIn(ExperimentalOpenIdConnect::class)
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication {
    return startKoin {
        logger(KermitKoinLogger(Level.DEBUG))
        includes(config)
        modules(commonClientModule(), module {
            singleOf(::TokenRefreshHandler)
            singleOf(::ClientCore)
            singleOf(::SessionManager)
            singleOf(::OidcAuthService) bind AuthService::class
            singleOf(::HttpClientDependencies)
            single { BackendReachability(get(), CoroutineScope(Dispatchers.Default + SupervisorJob())) }
            single { HttpClient { setUpMiddleWare(get())} }
            single(named("identity")) {
                RpcConnectionManager(get(), "ws://Moritzs-MacBook-Pro.local:8081/identity", get(), get(), CoroutineScope(Dispatchers.Default + SupervisorJob()))
            }
        })
    }
}