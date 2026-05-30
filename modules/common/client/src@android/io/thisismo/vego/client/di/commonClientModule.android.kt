package io.thisismo.vego.client.auth.infrastructure.di

import io.thisismo.vego.client.auth.infrastructure.network.NetworkMonitor
import io.thisismo.vego.client.datastore.AndroidDataStorePathProvider
import io.thisismo.vego.client.datastore.DataStorePathProvider
import io.thisismo.vego.client.io.NetworkMonitorAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual fun commonClientModule(): Module = module {
    single { NetworkMonitorAndroid(androidContext()) } bind NetworkMonitor::class
    single { AndroidDataStorePathProvider(androidContext()) } bind DataStorePathProvider::class
}