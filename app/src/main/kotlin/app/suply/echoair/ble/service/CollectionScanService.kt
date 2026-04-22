package app.suply.echoair.ble.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import app.suply.echoair.EchoAirApp
import app.suply.echoair.R
import app.suply.echoair.ble.CollectionOrchestrator
import app.suply.echoair.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the collection scan alive while the app is backgrounded or the phone
 * is locked. The ViewModel drives the orchestrator; this service exists only
 * to hold a foreground notification so Android doesn't kill the process.
 * Android 12+ requires this for reliable BLE scanning.
 */
@AndroidEntryPoint
class CollectionScanService : LifecycleService() {

    @Inject lateinit var orchestrator: CollectionOrchestrator

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        orchestrator.stop()
        super.onDestroy()
    }

    private fun startInForeground() {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, EchoAirApp.CHANNEL_COLLECTION)
            .setContentTitle(getString(R.string.notif_collection_title))
            .setContentText("Collecting shipment data")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        const val EXTRA_SHIPMENT_ID = "shipment_id"
        private const val NOTIFICATION_ID = 1001
    }
}
