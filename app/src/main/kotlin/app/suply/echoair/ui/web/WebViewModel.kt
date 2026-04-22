package app.suply.echoair.ui.web

import androidx.lifecycle.ViewModel
import app.suply.echoair.data.auth.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WebViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    val bridge: WebViewBridge
) : ViewModel() {
    fun jwt(): String? = tokenStore.current()
}
