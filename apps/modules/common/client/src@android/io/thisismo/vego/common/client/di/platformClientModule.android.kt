package io.thisismo.vego.common.client.di

import io.thisismo.vego.common.client.network.NetworkMonitor
import io.thisismo.vego.common.client.network.NetworkMonitorAndroid
import io.thisismo.vego.common.client.persistence.SqlDriverFactory
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.tokenstore.AndroidSettingsTokenStore
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

@OptIn(ExperimentalOpenIdConnect::class)
internal actual fun platformClientModule(): Module = module {
    singleOf(::SqlDriverFactory)
    singleOf(::AndroidSettingsTokenStore) bind TokenStore::class
    singleOf(::NetworkMonitorAndroid) bind NetworkMonitor::class
}