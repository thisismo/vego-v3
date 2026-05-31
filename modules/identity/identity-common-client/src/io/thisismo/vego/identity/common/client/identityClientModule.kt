package io.thisismo.vego.identity.common.client

import io.thisismo.vego.common.client.network.RpcConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin module that exposes the identity feature's client side dependencies.
 *
 * Each feature module ships its own Koin module (and its own SQLDelight database via
 * [IdentityDatabase] and [UserRepository]); the app simply includes the feature modules it needs
 * when bootstrapping Koin. The shared `SqlDriverFactory` keeps the database setup free of platform
 * boilerplate.
 */
fun identityClientModule(): Module = module {
    single { createIdentityDatabase(get()) }
    singleOf(::UserRepository) bind UserStore::class
    single { UserService(get(named("identity")), get()) }
    single(named("identity")) {
        RpcConnectionManager(
            get(),
            "ws://Moritzs-MacBook-Pro.local:8081/identity",
            get(),
            get(),
            CoroutineScope(Dispatchers.Default + SupervisorJob())
        )
    }
}
