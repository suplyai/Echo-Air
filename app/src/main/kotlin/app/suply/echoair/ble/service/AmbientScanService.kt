package app.suply.echoair.ble.service

import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.suply.echoair.EchoAirApp
import app.suply.echoair.R
import app.suply.echoair.ble.BleConnectionManager
import app.suply.echoair.ble.BleScanner
import app.suply.echoair.ble.KBeacon
import app.suply.echoair.data.ShipmentRepository
import app.suply.echoair.data.api.ReadingDto
import app.suply.echoair.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Ambient scanning service — runs whenever the user opts in. Keeps a cached
 * roster of device MACs / serials across the org's active shipments and
 * attempts connections when any of them stays in range long enough.
 *
 * Duty cycle: scan 10s every 2 minutes (tunable constants below).
 */
@AndroidEntryPoint
class AmbientScanService : LifecycleService() {

    @Inject lateinit var scanner: BleScanner
    @Inject lateinit var connection: BleConnectionManager
    @Inject lateinit var repo: ShipmentRepository

    private var scanJob: Job? = null
    private val lastSeen = ConcurrentHashMap<String, Long>()        // deviceId -> ts
    private val inFlight = ConcurrentHashMap.newKeySet<String>()     // deviceIds being downloaded

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (scanJob?.isActive != true) scanJob = lifecycleScope.launch { dutyCycle() }
        return START_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }

    private suspend fun dutyCycle() {
        // Warm the roster once on start, then re-fetch on every duty cycle
        // if the last refresh was more than ROSTER_REFRESH_MS ago. Without
        // this, shipments created on the web platform after the service
        // started would never enter the scan filter until the service
        // restarts.
        var lastRosterRefresh = 0L
        refreshRosterIfStale(lastRosterRefresh)?.let { lastRosterRefresh = it }

        while (currentCoroutineContext().isActive) {
            refreshRosterIfStale(lastRosterRefresh)?.let { lastRosterRefresh = it }

            val expectedMacs = repo.activeRosterMacs().map { it.uppercase() }.toSet()
            val expectedSerials = repo.activeRosterDeviceIds().toSet()

            if (expectedMacs.isEmpty() && expectedSerials.isEmpty()) {
                delay(IDLE_INTERVAL_MS)
                continue
            }

            val scanEnd = System.currentTimeMillis() + SCAN_WINDOW_MS
            val job = scanner.scan(mode = ScanSettings.SCAN_MODE_LOW_POWER)
                .filter { b -> b.mac.uppercase() in expectedMacs || b.serial in expectedSerials }
                .onEach { beacon -> onAmbientBeacon(beacon) }
                .launchIn(lifecycleScope)

            while (System.currentTimeMillis() < scanEnd && currentCoroutineContext().isActive) delay(500)
            job.cancel()

            delay(DUTY_INTERVAL_MS)
        }
    }

    /** Refreshes the roster if the last refresh is older than [ROSTER_REFRESH_MS]. Returns the new timestamp, or null if no refresh happened. */
    private suspend fun refreshRosterIfStale(lastRefreshAt: Long): Long? {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < ROSTER_REFRESH_MS) return null
        return runCatching { repo.refreshActiveRoster(); now }.getOrElse {
            Timber.w(it, "ambient roster refresh failed")
            null  // keep retrying next cycle
        }
    }

    private fun onAmbientBeacon(beacon: KBeacon) {
        val now = System.currentTimeMillis()
        val first = lastSeen.putIfAbsent(beacon.serial, now) ?: now
        lastSeen[beacon.serial] = now

        val stableFor = now - first
        if (stableFor >= STABLE_WINDOW_MS && beacon.serial !in inFlight) {
            lifecycleScope.launch { attemptDownload(beacon) }
        }
    }

    private suspend fun attemptDownload(beacon: KBeacon) {
        if (!inFlight.add(beacon.serial)) return
        try {
            val result = connection.downloadLog(beacon.mac)
            repo.submitRecords(
                deviceId = beacon.serial,
                records = result.records,
                deviceClockOffsetSeconds = result.deviceClockOffsetSeconds
            )
        } catch (t: Throwable) {
            Timber.w(t, "ambient download failed for ${beacon.serial}")
        } finally {
            inFlight.remove(beacon.serial)
            lastSeen.remove(beacon.serial)
        }
    }

    private fun startInForeground() {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, EchoAirApp.CHANNEL_AMBIENT)
            .setContentTitle(getString(R.string.notif_ambient_title))
            .setContentText(getString(R.string.notif_ambient_body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
        private const val SCAN_WINDOW_MS = 10_000L
        private const val DUTY_INTERVAL_MS = 120_000L       // 2 min
        private const val IDLE_INTERVAL_MS = 300_000L       // 5 min when no roster
        private const val ROSTER_REFRESH_MS = 30 * 60_000L  // 30 min
        private const val STABLE_WINDOW_MS = 30_000L        // connect after 30s in range
        private const val NOTIFICATION_ID = 1002
    }
}
