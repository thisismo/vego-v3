package io.thisismo.vego.identity.server.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.thisismo.vego.identity.server.application.AuthService
import io.thisismo.vego.identity.server.application.SessionService
import io.thisismo.vego.identity.server.domain.OAuthRefreshService
import io.thisismo.vego.identity.server.domain.SessionRepository
import io.thisismo.vego.identity.server.domain.UserRepository
import io.thisismo.vego.identity.server.infrastructure.oidc.OAuthRefreshServiceKtor
import io.thisismo.vego.identity.server.infrastructure.persistence.InMemorySessionRepository
import io.thisismo.vego.identity.server.infrastructure.persistence.InMemoryUserRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

internal val identityKoinModule = module {
    singleOf(::InMemoryUserRepository) bind UserRepository::class
    singleOf(::InMemorySessionRepository) bind SessionRepository::class
    singleOf(::AuthService)
    singleOf(::SessionService)
    single<AuthService> { AuthService(get()) }
    single<OAuthRefreshService> { OAuthRefreshServiceKtor(HttpClient(CIO), get()) }
}