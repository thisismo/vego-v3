package hello.world

import android.app.Application
import io.thisismo.vego.client.auth.infrastructure.di.commonClientModule
import io.thisismo.vego.client.auth.infrastructure.di.initKoin
import io.thisismo.vego.identity.common.client.identityClientModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidLogger()
            androidContext(this@MainApplication)
            modules(commonClientModule(), identityClientModule())
        }
    }
}
