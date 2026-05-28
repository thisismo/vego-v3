package io.thisismo.vego.client.auth.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.thisismo.vego.client.auth.IdentityServerConfig
import io.thisismo.vego.client.core.SessionManager
import io.thisismo.vego.client.io.NetworkMonitor
import io.thisismo.vego.client.io.NetworkStatus
import org.koin.compose.koinInject

@Composable
@Suppress("UNUSED_PARAMETER") // kept for callers in Screen.kt; SessionManager state is driven by the deeplink callback
fun LoginScreen(sessionManager: SessionManager) {
    val networkMonitor: NetworkMonitor = koinInject()
    val identityServerConfig: IdentityServerConfig = koinInject()
    val uriHandler = LocalUriHandler.current
    val networkStatus by networkMonitor.status.collectAsState()

    Text("Login Screen")
    if (networkStatus != NetworkStatus.Online) {
        Text("Info: No internet connection")
    }
    Button(
        onClick = { onLoginClicked(uriHandler, identityServerConfig) },
        enabled = networkStatus == NetworkStatus.Online,
    ) {
        Text("Login")
    }
}

/**
 * Extracted click handler so the login flow can be exercised in unit tests
 * without standing up the full Compose UI. Opens the identity server's login
 * URL in the platform's default browser via Compose Multiplatform's
 * [UriHandler], which automatically dispatches to `Intent.ACTION_VIEW` on
 * Android and `UIApplication.openURL` on iOS.
 *
 * After authenticating, the identity server redirects back into the app via
 * the `vego-v3://identity/callback?sessionToken=...` deeplink, which is
 * handled by `IdentityCallbackDeeplinkHandler`.
 */
internal fun onLoginClicked(uriHandler: UriHandler, config: IdentityServerConfig) {
    uriHandler.openUri(config.loginUrl)
}