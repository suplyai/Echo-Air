package app.suply.echoair.ble

import android.content.Context
import app.suply.echoair.data.api.ReadingDto
import com.kkmcn.kbeaconlib2.KBCfgPackage.KBCfgSensor
import com.kkmcn.kbeaconlib2.KBConnPara
import com.kkmcn.kbeaconlib2.KBConnState
import com.kkmcn.kbeaconlib2.KBException
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorReadUserCallback
import com.kkmcn.kbeaconlib2.KBeacon
import com.kkmcn.kbeaconlib2.KBeaconsMgr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles GATT connection + log download for a single device using kbeaconlib2.
 *
 * Android has a platform-level cap (typically 4–7) on concurrent GATT
 * connections. We enforce 4 here and queue the rest. Mid-download
 * disconnections are retried up to 3 times.
 */
@Singleton
class BleConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val slots = Semaphore(MAX_CONCURRENT)
    private val mgr: KBeaconsMgr? = KBeaconsMgr.sharedBeaconManager(context)

    data class DownloadProgress(val current: Int, val total: Int)

    /**
     * Connects to [mac], downloads the full temperature/humidity log, and
     * returns it as a list of [ReadingDto]. The optional [onProgress] callback
     * fires on each chunk so the UI can render a progress bar.
     */
    suspend fun downloadLog(
        mac: String,
        password: String = KBeaconIds.DEFAULT_PASSWORD,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): List<ReadingDto> = slots.withPermit {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                return@withPermit attemptDownload(mac, password, onProgress)
            } catch (t: Throwable) {
                lastError = t
                Timber.w(t, "Download attempt $attempt for $mac failed")
                disconnectQuietly(mac)
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    private suspend fun attemptDownload(
        mac: String,
        password: String,
        onProgress: ((DownloadProgress) -> Unit)?
    ): List<ReadingDto> = withContext(Dispatchers.IO) {
        val beacon = beaconFor(mac) ?: error("unknown beacon $mac")
        connect(beacon, password)
        try {
            readHistory(beacon, onProgress)
        } finally {
            disconnectQuietly(mac)
        }
    }

    private suspend fun connect(beacon: KBeacon, password: String) =
        suspendCancellableCoroutine { cont ->
            val para = KBConnPara().apply {
                this.syncUtcTime = true
                this.readCommPara = true
                this.readSensorPara = true
                this.readTriggerPara = false
                this.readSlotPara = false
            }
            beacon.connect(password, CONNECT_TIMEOUT_MS, para) { state, _, ex ->
                when (state) {
                    KBConnState.Connected -> if (!cont.isCompleted) cont.resume(Unit)
                    KBConnState.Disconnected -> if (!cont.isCompleted) {
                        cont.resumeWithException(ex ?: KBException(-1, "disconnected during connect"))
                    }
                    else -> Unit
                }
            }
        }

    private suspend fun readHistory(
        beacon: KBeacon,
        onProgress: ((DownloadProgress) -> Unit)?
    ): List<ReadingDto> = suspendCancellableCoroutine { cont ->
        val reader = beacon.sensorHistoryData ?: run {
            cont.resumeWithException(IllegalStateException("history service unavailable"))
            return@suspendCancellableCoroutine
        }

        val collected = mutableListOf<ReadingDto>()
        val callback = object : KBSensorReadUserCallback {
            override fun onReadComplete(totalRecord: Int) {
                Timber.d("Log read complete: $totalRecord records")
                cont.resume(collected.toList())
            }

            override fun onReadProgress(
                totalRecord: Int,
                readRecord: Int,
                records: Array<out com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordBase>?
            ) {
                records?.forEach { record ->
                    val temp = record.getField(KBCfgSensor.KBSensorTypeTemperature)
                    val humidity = record.getField(KBCfgSensor.KBSensorTypeHumidity)
                    val ts = record.utcTime
                    if (temp != null && ts > 0) {
                        collected.add(
                            ReadingDto(
                                temperature = temp.toDouble(),
                                humidity = humidity?.toDouble(),
                                timestamp = ts
                            )
                        )
                    }
                }
                onProgress?.invoke(DownloadProgress(readRecord, totalRecord))
            }

            override fun onReadFailed(error: KBException?) {
                cont.resumeWithException(error ?: IllegalStateException("read failed"))
            }
        }

        try {
            reader.readSensorRecord(
                KBCfgSensor.KBSensorTypeTemperature or KBCfgSensor.KBSensorTypeHumidity,
                SensorHistoryReadOptions.READ_ALL,
                callback
            )
        } catch (t: Throwable) {
            cont.resumeWithException(t)
        }
    }

    private fun beaconFor(mac: String): KBeacon? {
        val formatted = if (mac.contains(":")) mac.uppercase()
                        else mac.uppercase().chunked(2).joinToString(":")
        return mgr?.getBeacon(formatted)
    }

    private fun disconnectQuietly(mac: String) {
        runCatching { beaconFor(mac)?.disconnect() }
    }

    private companion object {
        const val MAX_CONCURRENT = 4
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}

private object SensorHistoryReadOptions {
    /** Read from newest to oldest, all records. */
    const val READ_ALL: Int = 0
}
