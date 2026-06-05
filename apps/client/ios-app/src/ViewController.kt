
import androidx.compose.ui.window.ComposeUIViewController
import io.thisismo.vego.client.Screen
import io.thisismo.vego.client.core.di.initKoin

fun ViewController() = ComposeUIViewController { Screen() }

/**
 * iOS entry point for Koin. Bootstraps the shared client graph and additionally registers the
 * identity feature module (and its per-feature SQLDelight database via UserRepository). Future feature modules can
 * be added here the same way.
 */
fun initKoinIosApp() = initKoin()