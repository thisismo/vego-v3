package io.thisismo.vego.client.di

import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkMonitorIOS
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual fun commonClientModule(): Module = module {
    single { NetworkMonitorIOS() } bind NetworkMonitor::class
}

fun initKoinIos() = initKoin()