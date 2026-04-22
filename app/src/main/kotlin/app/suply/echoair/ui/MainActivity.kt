package app.suply.echoair.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.suply.echoair.ui.capture.CaptureScreen
import app.suply.echoair.ui.collection.CollectionScreen
import app.suply.echoair.ui.home.HomeScreen
import app.suply.echoair.ui.login.LoginScreen
import app.suply.echoair.ui.settings.SettingsScreen
import app.suply.echoair.ui.web.WebScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EchoAirNavHost()
                }
            }
        }
    }
}

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val COLLECTION = "collection/{shipmentId}"
    const val SETTINGS = "settings"
    const val WEB = "web?path={path}&title={title}"

    fun collection(shipmentId: String) = "collection/$shipmentId"
    fun web(path: String, title: String = "") =
        "web?path=${java.net.URLEncoder.encode(path, "UTF-8")}&title=${java.net.URLEncoder.encode(title, "UTF-8")}"
}

@Composable
private fun EchoAirNavHost() {
    val nav = rememberNavController()
    val gate: GateViewModel = hiltViewModel()
    val start = if (gate.isAuthenticated()) Routes.HOME else Routes.LOGIN

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(onAuthenticated = {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onStartCapture = { nav.navigate(Routes.CAPTURE) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenShipment = { id -> nav.navigate(Routes.collection(id)) },
                onOpenWeb = { path, title -> nav.navigate(Routes.web(path, title)) }
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onCancel = { nav.popBackStack() },
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
                onClose = {
                    nav.popBackStack(route = Routes.HOME, inclusive = false)
                },
                onOpenWeb = { path, title -> nav.navigate(Routes.web(path, title)) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onLoggedOut = {
                nav.navigate(Routes.LOGIN) {
                    popUpTo(0)
                }
            })
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
                        is app.suply.echoair.ui.web.WebViewBridge.Command.OpenCapture ->
                            nav.navigate(Routes.CAPTURE)
                        is app.suply.echoair.ui.web.WebViewBridge.Command.StartCollection ->
                            nav.navigate(Routes.collection(cmd.shipmentId))
                        is app.suply.echoair.ui.web.WebViewBridge.Command.Logout ->
                            nav.navigate(Routes.LOGIN) { popUpTo(0) }
                    }
                }
            )
        }
    }
}
