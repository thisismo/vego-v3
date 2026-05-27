package io.thisismo.vego.identity.server.di

import io.thisismo.vego.identity.server.application.AuthService
import io.thisismo.vego.identity.server.domain.UserRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

internal val koinModules = module {
    singleOf(::UserRepository)
    single<AuthService> { AuthService(get()) }
}