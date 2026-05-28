package hello.world

import io.thisismo.vego.client.Screen
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.thisismo.vego.client.deeplink.DeeplinkRouter
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val deeplinkRouter: DeeplinkRouter by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeeplinkIntent(intent)
        setContent {
            Screen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeeplinkIntent(intent)
    }

    private fun handleDeeplinkIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        deeplinkRouter.dispatch(data.toString())
    }
}
