package app.suply.echoair.ui.settings

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import app.suply.echoair.ble.service.AmbientScanService
import app.suply.echoair.data.auth.TokenStore
import app.suply.echoair.data.prefs.UserPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    app: Application,
    private val prefs: UserPrefs,
    private val tokenStore: TokenStore
) : AndroidViewModel(app) {

    val ambient: StateFlow<Boolean> = prefs.ambientFlow

    fun email(): String? = tokenStore.email()

    fun setAmbient(enabled: Boolean) {
        prefs.setAmbientEnabled(enabled)
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, AmbientScanService::class.java)
        if (enabled) ContextCompat.startForegroundService(ctx, intent)
        else ctx.stopService(intent)
    }

    fun logout() {
        tokenStore.clear()
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, AmbientScanService::class.java))
    }
}
