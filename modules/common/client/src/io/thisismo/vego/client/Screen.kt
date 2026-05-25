package io.thisismo.vego.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.thisismo.vego.client.io.NetworkMonitor
import org.koin.compose.koinInject

@Composable
fun Screen() {
    val networkMonitor: NetworkMonitor = koinInject()
    LaunchedEffect(Unit) {
        networkMonitor.initialize()
    }
    val isOnline by networkMonitor.isOnline.collectAsState()
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("Hello, ${if (isOnline) "online" else "offline"}!")
        }
    }
}
