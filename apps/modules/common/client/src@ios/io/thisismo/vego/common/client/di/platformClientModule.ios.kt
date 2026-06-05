package io.thisismo.vego.common.client.di

import io.thisismo.vego.common.client.network.NetworkMonitor
import io.thisismo.vego.common.client.network.NetworkMonitorIOS
import io.thisismo.vego.common.client.persistence.SqlDriverFactory
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
internal actual fun platformClientModule(): Module = module {
    singleOf(::SqlDriverFactory)
    single<CodeAuthFlowFactory> {
        IosCodeAuthFlowFactory()
    }
    singleOf(::NetworkMonitorIOS) bind NetworkMonitor::class
    singleOf(::IosKeychainTokenStore) bind TokenStore::class
}