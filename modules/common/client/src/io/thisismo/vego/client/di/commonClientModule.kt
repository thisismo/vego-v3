package io.thisismo.vego.client.di

import io.thisismo.vego.client.auth.AuthService
import io.thisismo.vego.client.auth.OidcAuthService
import io.thisismo.vego.client.core.ClientCore
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.client.io.BackendReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            singleOf(::SessionManager)
            singleOf(::OidcAuthService) bind AuthService::class
            single { BackendReachability(get(), CoroutineScope(Dispatchers.Default + SupervisorJob())) }
        })
    }
}