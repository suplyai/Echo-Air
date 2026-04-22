package app.suply.echoair.ble.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.suply.echoair.data.prefs.UserPrefs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Restarts ambient scanning after device reboot or app upgrade, but only if
 * the user has opted in.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: UserPrefs

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        try {
            if (prefs.ambientEnabled()) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AmbientScanService::class.java)
                )
            }
        } finally {
            pending.finish()
        }
    }
}
