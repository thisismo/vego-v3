import androidx.compose.ui.window.ComposeUIViewController
import io.thisismo.vego.client.Screen
import io.thisismo.vego.client.auth.infrastructure.di.initKoin
import io.thisismo.vego.identity.common.client.identityClientModule

fun ViewController() = ComposeUIViewController { Screen() }

/**
 * iOS entry point for Koin. Bootstraps the shared client graph and additionally registers the
 * identity feature module (and its per-feature [UserStore] DataStore). Future feature modules can
 * be added here the same way.
 */
fun initKoinIosApp() = initKoin {
    modules(identityClientModule())
}