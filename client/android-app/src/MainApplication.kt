package hello.world

import android.app.Application
import io.thisismo.vego.client.di.commonClientModule
import io.thisismo.vego.client.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidLogger()
            androidContext(this@MainApplication)
            modules(commonClientModule())
        }
    }
}
