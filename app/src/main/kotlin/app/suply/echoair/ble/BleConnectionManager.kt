package app.suply.echoair.ble

import android.content.Context
import app.suply.echoair.data.api.ReadingDto
import com.kkmcn.kbeaconlib2.KBCfgPackage.KBSensorType
import com.kkmcn.kbeaconlib2.KBConnPara
import com.kkmcn.kbeaconlib2.KBConnState
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordDataRsp
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordHumidity
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorReadOption
import com.kkmcn.kbeaconlib2.KBeacon
import com.kkmcn.kbeaconlib2.KBeaconsMgr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val beacon = resolveBeacon(mac) ?: error("unknown beacon $mac")
        connect(beacon, password)
        try {
            // Single sensor type on S23/S23H — HTHumidity covers both. The
            // humidity field in KBRecordHumidity is zero / unpopulated on
            // temperature-only S23 variants but the record class is still
            // what the library returns.
            val sensorType = KBSensorType.HTHumidity

            // Step 1: query totals + device clock
            val info = readSensorDataInfo(beacon, sensorType)
            val phoneUtcSeconds = System.currentTimeMillis() / 1000
            val deviceUtc = info.readInfoUtcSeconds ?: phoneUtcSeconds
            val clockOffset = phoneUtcSeconds - deviceUtc
            val total = info.totalRecordNumber ?: 0
            Timber.d(
                "Device %s: total=%d unread=%d deviceUtc=%d phoneUtc=%d offset=%ds",
                mac, total, info.unreadRecordNumber ?: -1, deviceUtc, phoneUtcSeconds, clockOffset
            )
            onProgress?.invoke(DownloadProgress(0, total))

            // Step 2: paged reads in NormalOrder, starting from INVALID_DATA_RECORD_POS.
            //
            // KKM's docs say INVALID_DATA_RECORD_POS is the correct start
            // cursor for a full replay (confirmed as 4294967295L / unsigned
            // 32-bit max from the library source, not -1). If we get an
            // empty first batch while the device reports total > 0, retry
            // once from startPos = 0. The spike harness is the canonical
            // place to verify which value the library actually wants; this
            // fallback just stops the rare mismatch from silently producing
            // zero-record uploads.
            val collected = ArrayList<ReadingDto>(total.coerceAtLeast(0))
            var nextPos = KBRecordDataRsp.INVALID_DATA_RECORD_POS
            var firstBatch = true
            while (collected.size < total) {
                var batch = readSensorBatch(beacon, sensorType, nextPos, BATCH_SIZE)
                if (firstBatch && batch.records.isEmpty() && total > 0) {
                    Timber.w("First batch empty at startPos=%d; retrying from 0", nextPos)
                    batch = readSensorBatch(beacon, sensorType, 0L, BATCH_SIZE)
                }
                firstBatch = false
                if (batch.records.isEmpty()) break   // device said done
                collected.addAll(batch.records)
                nextPos = batch.nextPos
                onProgress?.invoke(DownloadProgress(collected.size, total))
                if (batch.done || nextPos == KBRecordDataRsp.INVALID_DATA_RECORD_POS) break
            }
            LogReadResult(records = collected, deviceClockOffsetSeconds = clockOffset)
        } finally {
            disconnectQuietly(mac)
        }
    }

    private suspend fun connect(beacon: KBeacon, password: String) =
        suspendCancellableCoroutine { cont ->
            val para = KBConnPara().apply {
                syncUtcTime = false       // preserve drifted clock so we can capture the offset
                readCommPara = true        // triggers MTU negotiation + common-cfg read
                readSensorPara = true
                readTriggerPara = false
                readSlotPara = false
            }
            beacon.connectEnhanced(password, CONNECT_TIMEOUT_MS, para) { _, state, nReason ->
                when (state) {
                    KBConnState.Connected -> if (!cont.isCompleted) cont.resume(Unit)
                    KBConnState.Disconnected -> if (!cont.isCompleted) {
                        cont.resumeWithException(
                            RuntimeException("disconnected during connect (reason=$nReason)")
                        )
                    }
                    else -> Unit
                }
            }
        }

    private suspend fun readSensorDataInfo(
        beacon: KBeacon, sensorType: Int
    ): com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordInfoRsp =
        suspendCancellableCoroutine { cont ->
            beacon.readSensorDataInfo(sensorType) { success, info, error ->
                if (success && info != null) cont.resume(info)
                else cont.resumeWithException(error ?: IllegalStateException("readSensorDataInfo failed"))
            }
        }

    private suspend fun readSensorBatch(
        beacon: KBeacon,
        sensorType: Int,
        startPos: Long,
        maxRecords: Int
    ): Batch = suspendCancellableCoroutine { cont ->
        beacon.readSensorRecord(
            sensorType,
            startPos,
            KBSensorReadOption.NormalOrder,
            maxRecords
        ) { success, rsp, error ->
            if (!success || rsp == null) {
                cont.resumeWithException(error ?: IllegalStateException("readSensorRecord failed"))
                return@readSensorRecord
            }
            val records = rsp.readDataRspList?.mapNotNull { raw ->
                val r = raw as? KBRecordHumidity ?: return@mapNotNull null
                if (r.utcTime <= 0) null
                else ReadingDto(
                    temperature = r.temperature.toDouble(),
                    humidity = r.humidity.toDouble(),
                    timestamp = r.utcTime
                )
            } ?: emptyList()
            val nextPos = rsp.readDataNextPos ?: KBRecordDataRsp.INVALID_DATA_RECORD_POS
            cont.resume(
                Batch(
                    records = records,
                    nextPos = nextPos,
                    done = nextPos == KBRecordDataRsp.INVALID_DATA_RECORD_POS
                )
            )
        }
    }

    private fun beaconFor(mac: String): KBeacon? {
        val formatted = if (mac.contains(":")) mac.uppercase()
                        else mac.uppercase().chunked(2).joinToString(":")
        return mgr?.getBeacon(formatted)
    }

    /**
     * Prime KBeaconsMgr's internal beacon cache before attempting to connect.
     *
     * DO NOT REMOVE — this warmup is load-bearing. KBeaconsMgr.getBeacon(mac)
     * only returns beacons that the library's *own* scanner discovered via
     * KBeaconsMgr.startScanning. Our primary BleScanner uses Android's
     * native BluetoothLeScanner for lower overhead in the collection UI,
     * which means the KBeaconsMgr cache is typically cold when we try to
     * connect. Without this short startScanning window, getBeacon returns
     * null and every download fails with "unknown beacon <mac>" even
     * though the device is clearly in range.
     */
    private suspend fun resolveBeacon(mac: String): KBeacon? {
        beaconFor(mac)?.let { return it }
        val m = mgr ?: return null
        runCatching { m.startScanning() }
        try {
            val deadline = System.currentTimeMillis() + WARMUP_MS
            while (System.currentTimeMillis() < deadline) {
                beaconFor(mac)?.let { return it }
                delay(250)
            }
        } finally {
            runCatching { m.stopScanning() }
        }
        return beaconFor(mac)
    }

    private fun disconnectQuietly(mac: String) {
        runCatching { beaconFor(mac)?.disconnect() }
    }

    private data class Batch(
        val records: List<ReadingDto>,
        val nextPos: Long,
        val done: Boolean
    )

    private companion object {
        const val MAX_CONCURRENT = 4           // platform cap is typically 4–7
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 20_000
        const val WARMUP_MS = 8_000L           // short KBeaconsMgr scan to prime getBeacon cache
        const val BATCH_SIZE = 200             // tune 100–500 based on stability
    }
}
