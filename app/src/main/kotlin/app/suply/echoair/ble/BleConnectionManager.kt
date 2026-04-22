package app.suply.echoair.ble

import android.content.Context
import app.suply.echoair.data.api.ReadingDto
import com.kkmcn.kbeaconlib2.KBConnPara
import com.kkmcn.kbeaconlib2.KBConnState
import com.kkmcn.kbeaconlib2.KBException
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordHumidity
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorReadOption
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorType
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
 * GATT connection + log download for a single device using kbeaconlib2.
 *
 * Throughput reality check: KKM's 7,000 rec / 15s number is for their
 * dedicated KGateway hardware. Consumer Android phones see 5–15 minutes
 * for a full 60,000-record log; Samsung tends to be fastest, Xiaomi and
 * Huawei slowest. Android's platform GATT cap is typically 4–7, so we
 * limit ourselves to 4 concurrent slots and read the rest sequentially.
 * kbeaconlib2 negotiates a larger MTU at connection setup internally
 * during [KBConnPara.readCommPara].
 *
 * Retrieval uses the two-step pattern from KKM's KBeaconProDemo_Android
 * §4.3.6: first readSensorDataInfo to get the total + device UTC clock,
 * then readSensorRecord in NormalOrder batches paged via the
 * readDataNextPos cursor. NormalOrder is critical — Echo Air devices are
 * single-use, so we always want the full history and never want to
 * advance the on-device "unread" pointer (which is what NewRecord does).
 */
@Singleton
class BleConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class DownloadProgress(val current: Int, val total: Int)

    /**
     * Records pulled from the device plus the clock offset captured at the
     * start of readout. The backend uses the offset to correct timestamps
     * against independent evidence (waybill milestones, Hubble scans);
     * the app never mutates timestamps client-side.
     */
    data class LogReadResult(
        val records: List<ReadingDto>,
        val deviceClockOffsetSeconds: Long
    )

    private val slots = Semaphore(MAX_CONCURRENT)
    private val mgr: KBeaconsMgr? = KBeaconsMgr.sharedBeaconManager(context)

    suspend fun downloadLog(
        mac: String,
        password: String = KBeaconIds.DEFAULT_PASSWORD,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): LogReadResult = slots.withPermit {
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
    ): LogReadResult = withContext(Dispatchers.IO) {
        val beacon = beaconFor(mac) ?: error("unknown beacon $mac")
        connect(beacon, password)
        try {
            val sensorType = pickSensorType(beacon)

            // Step 1: query totals + device clock
            val info = readSensorDataInfo(beacon, sensorType)
            val phoneUtcSeconds = System.currentTimeMillis() / 1000
            val clockOffset = phoneUtcSeconds - info.readInfoUtcSeconds
            val total = info.totalRecordNumber
            Timber.d(
                "Device %s: total=%d unread=%d deviceUtc=%d phoneUtc=%d offset=%ds",
                mac, total, info.unreadRecordNumber, info.readInfoUtcSeconds, phoneUtcSeconds, clockOffset
            )
            onProgress?.invoke(DownloadProgress(0, total))

            // Step 2: paged reads in NormalOrder, starting from INVALID_DATA_RECORD_POS
            val collected = ArrayList<ReadingDto>(total.coerceAtLeast(0))
            var nextPos = INVALID_DATA_RECORD_POS
            while (collected.size < total) {
                val batch = readSensorBatch(beacon, sensorType, nextPos, BATCH_SIZE)
                if (batch.records.isEmpty()) break   // defensive: device said done
                collected.addAll(batch.records)
                nextPos = batch.nextPos
                onProgress?.invoke(DownloadProgress(collected.size, total))
                if (batch.done || nextPos == INVALID_DATA_RECORD_POS) break
            }
            LogReadResult(records = collected, deviceClockOffsetSeconds = clockOffset)
        } finally {
            disconnectQuietly(mac)
        }
    }

    private fun pickSensorType(beacon: KBeacon): Int {
        val common = beacon.commonCfg
        val supportsHumidity = common?.isSupportHumiditySensor == true
        return if (supportsHumidity) KBSensorType.HTHumidity else KBSensorType.Temperature
    }

    private suspend fun connect(beacon: KBeacon, password: String) =
        suspendCancellableCoroutine { cont ->
            val para = KBConnPara().apply {
                syncUtcTime = false       // don't overwrite the device clock — we *want* the drift
                readCommPara = true        // triggers MTU negotiation + common-cfg read
                readSensorPara = true
                readTriggerPara = false
                readSlotPara = false
            }
            beacon.connect(password, CONNECT_TIMEOUT_MS, para) { _, state, ex ->
                when (state) {
                    KBConnState.Connected -> if (!cont.isCompleted) cont.resume(Unit)
                    KBConnState.Disconnected -> if (!cont.isCompleted) {
                        cont.resumeWithException(ex ?: KBException(-1, "disconnected during connect"))
                    }
                    else -> Unit
                }
            }
        }

    private suspend fun readSensorDataInfo(beacon: KBeacon, sensorType: Int): SensorInfo =
        suspendCancellableCoroutine { cont ->
            val reader = beacon.sensorHistoryData
                ?: return@suspendCancellableCoroutine cont.resumeWithException(
                    IllegalStateException("history service unavailable")
                )
            reader.readSensorDataInfo(sensorType) { success, ex, dataInfo ->
                if (success && dataInfo != null) {
                    cont.resume(
                        SensorInfo(
                            totalRecordNumber = dataInfo.totalRecordNumber,
                            unreadRecordNumber = dataInfo.unreadRecordNumber,
                            readInfoUtcSeconds = dataInfo.readInfoUtcSeconds
                        )
                    )
                } else {
                    cont.resumeWithException(ex ?: IllegalStateException("readSensorDataInfo failed"))
                }
            }
        }

    private suspend fun readSensorBatch(
        beacon: KBeacon,
        sensorType: Int,
        startPos: Int,
        maxRecords: Int
    ): Batch = suspendCancellableCoroutine { cont ->
        val reader = beacon.sensorHistoryData
            ?: return@suspendCancellableCoroutine cont.resumeWithException(
                IllegalStateException("history service unavailable")
            )
        reader.readSensorRecord(
            sensorType,
            startPos,
            KBSensorReadOption.NormalOrder,
            maxRecords
        ) { success, ex, rsp ->
            if (!success || rsp == null) {
                cont.resumeWithException(ex ?: IllegalStateException("readSensorRecord failed"))
                return@readSensorRecord
            }
            val records = rsp.readDataRsp?.mapNotNull { raw ->
                val r = raw as? KBRecordHumidity ?: return@mapNotNull null
                val ts = r.utcTime
                val temp = r.temperature
                if (ts <= 0) null
                else ReadingDto(
                    temperature = temp.toDouble(),
                    humidity = r.humidity?.toDouble(),
                    timestamp = ts
                )
            } ?: emptyList()
            cont.resume(
                Batch(
                    records = records,
                    nextPos = rsp.readDataNextPos,
                    done = rsp.readDataNextPos == INVALID_DATA_RECORD_POS
                )
            )
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

    private data class SensorInfo(
        val totalRecordNumber: Int,
        val unreadRecordNumber: Int,
        val readInfoUtcSeconds: Long
    )

    private data class Batch(
        val records: List<ReadingDto>,
        val nextPos: Int,
        val done: Boolean
    )

    private companion object {
        const val MAX_CONCURRENT = 4           // platform cap is typically 4–7
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 20_000
        const val BATCH_SIZE = 200             // tune 100–500 based on stability
        const val INVALID_DATA_RECORD_POS = -1
    }
}
