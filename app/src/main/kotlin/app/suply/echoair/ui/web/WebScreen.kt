package app.suply.echoair.ui.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(
    path: String,
    title: String,
    onBack: () -> Unit,
    onBridgeCommand: (WebViewBridge.Command) -> Unit = {},
    vm: WebViewModel = hiltViewModel()
) {
    val bridge = remember { vm.bridge }

    LaunchedEffect(bridge) {
        bridge.commands.collect { onBridgeCommand(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "Suply" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                @SuppressLint("SetJavaScriptEnabled")
                val view = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.setSupportMultipleWindows(false)
                    webViewClient = WebViewClient()
                    addJavascriptInterface(bridge, "EchoAirBridge")
                }

                val baseUrl = BuildConfig.WEB_BASE_URL.trimEnd('/')
                val normalisedPath = if (path.startsWith("/")) path else "/$path"
                val url = "$baseUrl$normalisedPath"

                val token = vm.jwt()
                if (!token.isNullOrBlank()) {
                    val host = android.net.Uri.parse(baseUrl).host
                    if (host != null) {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setCookie("https://$host", "suply_jwt=$token; Path=/; Secure; SameSite=None")
                        cm.flush()
                    }
                }

                view.loadUrl(url, mapOf("Authorization" to "Bearer $token").filterValues { it.isNotBlank() })
                view
            }
        )
    }
}
