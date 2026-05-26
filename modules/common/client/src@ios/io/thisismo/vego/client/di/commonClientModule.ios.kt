package io.thisismo.vego.client.di

import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkMonitorIOS
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.appsupport.IosCodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.tokenstore.IosKeychainTokenStore
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

@OptIn(ExperimentalOpenIdConnect::class)
actual fun commonClientModule(): Module = module {
    singleOf(::NetworkMonitorIOS) bind NetworkMonitor::class
    single { IosCodeAuthFlowFactory() } bind CodeAuthFlowFactory::class
    singleOf(::IosKeychainTokenStore) bind TokenStore::class
}

fun initKoinIos() = initKoin()