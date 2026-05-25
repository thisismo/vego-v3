package io.thisismo.vego.client.di

import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkMonitorAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual fun commonClientModule(): Module = module {
    single { NetworkMonitorAndroid(androidContext()) } bind NetworkMonitor::class
}