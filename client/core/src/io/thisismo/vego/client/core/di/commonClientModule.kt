package io.thisismo.vego.client.core.di

import io.thisismo.vego.client.core.ClientCore
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.common.client.EndSessionHandler
import io.thisismo.vego.common.client.commonModule
import io.thisismo.vego.identity.common.client.identityClientModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.logger.KOIN_TAG
import org.koin.core.logger.Level
import org.koin.core.logger.MESSAGE
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.bind
import org.koin.dsl.includes
import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import co.touchlab.kermit.Logger as KermitLogger
import org.koin.core.logger.Logger as KoinLogger

private class KermitKoinLogger(level: Level = Level.DEBUG) : KoinLogger(level) {
    private val kermit = KermitLogger.withTag(KOIN_TAG)
    override fun display(level: Level, msg: MESSAGE) {
        when (level) {
            Level.DEBUG -> kermit.d { msg }
            Level.INFO -> kermit.i { msg }
            Level.WARNING -> kermit.w { msg }
            Level.ERROR -> kermit.e { msg }
            Level.NONE -> {}
        }
    }
}

@OptIn(ExperimentalOpenIdConnect::class)
fun initKoin(config: KoinAppDeclaration? = null): KoinApplication {
    return startKoin {
        logger(KermitKoinLogger(Level.DEBUG))
        includes(config)
        modules(module {
            singleOf(::ClientCore)
            singleOf(::SessionManager) bind EndSessionHandler::class
        }, commonModule(), identityClientModule())
    }
}