package io.thisismo.vego.client.di

import io.thisismo.vego.client.core.ClientCore
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.includes
import org.koin.dsl.module

expect fun commonClientModule(): Module

fun initKoin(config: KoinAppDeclaration? = null): KoinApplication {
    return startKoin {
        includes(config)
        modules(commonClientModule(), module {
            singleOf(::ClientCore)
        })
    }
}