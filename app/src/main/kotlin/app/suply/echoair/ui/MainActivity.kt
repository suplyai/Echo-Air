package app.suply.echoair.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.suply.echoair.ui.awb.ManualAwbScreen
import app.suply.echoair.ui.capture.CaptureMode
import app.suply.echoair.ui.capture.CaptureScreen
import app.suply.echoair.ui.collection.CollectionScreen
import app.suply.echoair.ui.home.HomeScreen
import app.suply.echoair.ui.locale.FirstLaunchLanguageGate
import app.suply.echoair.ui.locale.LocaleManager
import app.suply.echoair.ui.locale.wrapForLocale
import app.suply.echoair.ui.settings.SettingsScreen
import app.suply.echoair.ui.web.WebScreen
import app.suply.echoair.ui.web.WebViewBridge
import dagger.hilt.android.AndroidEntryPoint

/**
 * The Echo Air app is fully stateless. There is no login flow — the device
 * is the credential. A device's MAC and serial are physically printed on it
 * and pre-registered against a shipment by the shipper on the Suply web
 * platform before it leaves origin. By the time the consignee scans it at
 * destination, everything about it (owning org, target shipment, cold-chain
 * profile) is already established server-side; the phone is just a transport
 * layer.
 *
 * See the project README / brief for the full rationale.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // AppCompatDelegate.setApplicationLocales only *persists* the choice on a
    // Compose-only ComponentActivity — it doesn't actually wrap the Activity's
    // Resources the way AppCompatActivity would. So we own the apply here:
    // on every (re)creation, read the stored tag and hand super a Context
    // whose Configuration carries the chosen Locale. Pair with recreate()
    // in the language picker callers for an immediate UI switch.
    override fun attachBaseContext(newBase: Context) {
        val stored = LocaleManager.storedLocale(newBase)
        super.attachBaseContext(if (stored != null) newBase.wrapForLocale(stored) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = LocalContext.current
                    var firstLaunchNeeded by remember {
                        mutableStateOf(!LocaleManager.hasConfirmedFirstLaunch(context))
                    }
                    EchoAirNavHost()
                    if (firstLaunchNeeded) {
                        FirstLaunchLanguageGate(onAcknowledged = { firstLaunchNeeded = false })
                    }
                }
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture/{mode}"
    const val AWB_ENTRY = "awb_entry"
    const val COLLECTION = "collection/{shipmentId}"
    const val SETTINGS = "settings"
    const val WEB = "web?path={path}&title={title}"

    fun capture(mode: CaptureMode) = "capture/${mode.name}"
    fun collection(shipmentId: String) = "collection/$shipmentId"
    fun web(path: String, title: String = "") =
        "web?path=${java.net.URLEncoder.encode(path, "UTF-8")}&title=${java.net.URLEncoder.encode(title, "UTF-8")}"
}

@Composable
private fun EchoAirNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onScanQr = { nav.navigate(Routes.capture(CaptureMode.QR)) },
                onEnterAwb = { nav.navigate(Routes.AWB_ENTRY) },
                onScanDocumentDebug = { nav.navigate(Routes.capture(CaptureMode.DOCUMENT)) }
            )
        }
        composable(Routes.CAPTURE) { entry ->
            val mode = entry.arguments?.getString("mode")
                ?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() }
                ?: CaptureMode.QR
            CaptureScreen(
                mode = mode,
                onCancel = { nav.popBackStack() },
                onShipmentReady = { id ->
                    nav.navigate(Routes.collection(id)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }
        composable(Routes.AWB_ENTRY) {
            ManualAwbScreen(
                onBack = { nav.popBackStack() },
                onShipmentReady = { id ->
                    nav.navigate(Routes.collection(id)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }
        composable(Routes.COLLECTION) { entry ->
            val id = entry.arguments?.getString("shipmentId") ?: return@composable
            CollectionScreen(
                shipmentId = id,
                onClose = { nav.popBackStack(route = Routes.HOME, inclusive = false) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.WEB) { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            val title = entry.arguments?.getString("title").orEmpty()
            WebScreen(
                path = path,
                title = title,
                onBack = { nav.popBackStack() },
                onBridgeCommand = { cmd ->
                    when (cmd) {
                        is WebViewBridge.Command.OpenCapture ->
                            nav.navigate(Routes.capture(CaptureMode.QR))
                        is WebViewBridge.Command.StartCollection ->
                            nav.navigate(Routes.collection(cmd.shipmentId))
                    }
                }
            )
        }
    }
}
