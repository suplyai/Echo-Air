package app.suply.echoair.ui.web

import android.webkit.JavascriptInterface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JavaScript interface exposed to the WebView as `window.EchoAirBridge`.
 * The web UI calls these to trigger native flows; native pushes updates back
 * via [WebViewBridge.emit] + evaluateJavascript("window.EchoAirBridge._on(...)").
 */
@Singleton
class WebViewBridge @Inject constructor() {

    sealed interface Command {
        data object OpenCapture : Command
        data class StartCollection(val shipmentId: String) : Command
    }

    private val _commands = MutableSharedFlow<Command>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    @JavascriptInterface
    fun openCapture() {
        Timber.d("JS -> openCapture")
        _commands.tryEmit(Command.OpenCapture)
    }

    @JavascriptInterface
    fun startCollection(shipmentId: String) {
        Timber.d("JS -> startCollection($shipmentId)")
        _commands.tryEmit(Command.StartCollection(shipmentId))
    }
}
