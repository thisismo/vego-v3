package io.thisismo.vego.common.client

import io.ktor.client.*
import io.thisismo.vego.common.client.auth.AuthService
import io.thisismo.vego.common.client.auth.OidcAuthService
import io.thisismo.vego.common.client.di.platformClientModule
import io.thisismo.vego.common.client.network.BackendReachability
import io.thisismo.vego.common.client.network.HttpClientDependencies
import io.thisismo.vego.common.client.network.setUpMiddleWare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.tokenstore.TokenRefreshHandler

@OptIn(ExperimentalOpenIdConnect::class)
fun commonModule(): Module = module {
    includes(platformClientModule())
    singleOf(::TokenRefreshHandler)
    singleOf(::OidcAuthService) bind AuthService::class
    singleOf(::HttpClientDependencies)
    single { HttpClient { setUpMiddleWare(get())} }
    single { BackendReachability(get(), CoroutineScope(Dispatchers.Default + SupervisorJob())) }
}