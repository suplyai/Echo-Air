package app.suply.echoair.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
    private val btAdapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

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

            // Step 2: paged reads in NormalOrder, starting from 0L.
            //
            // Real-hardware spike on an S23H (serial 633640, fw from Apr 2026)
            // confirmed that KBRecordDataRsp.INVALID_DATA_RECORD_POS throws
            // "Read sensor record from device failed" when used as the initial
            // cursor, even though the library's own constant claims otherwise.
            // KKM's KBeaconProDemo_Android §4.3.6 uses 0L as the initial
            // NormalOrder cursor, which matches observed behaviour. Subsequent
            // batches use readDataNextPos from each response.
            //
            // If you see this start to fail with an empty first batch on new
            // firmware, check the spike harness output — it's the canonical
            // place we verify cursor semantics.
            val collected = ArrayList<ReadingDto>(total.coerceAtLeast(0))
            var nextPos = 0L
            while (collected.size < total) {
                val batch = readSensorBatch(beacon, sensorType, nextPos, BATCH_SIZE)
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
                // syncUtcTime MUST stay false for destination collection.
                //
                // Setting this to true would push the phone's UTC into the
                // device's RTC during the connection handshake, overwriting
                // the drifted clock we specifically want to measure. We need
                // that drift to reach /api/echo-scan as
                // device_clock_offset_seconds so the backend's fusion layer
                // can correlate independent evidence streams.
                //
                // Origin activation (web-platform flow, not this app) IS the
                // place where syncUtcTime = true — see docs/operating-profile.md.
                syncUtcTime = false
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

    private fun formatMac(mac: String): String =
        if (mac.contains(":")) mac.uppercase()
        else mac.uppercase().chunked(2).joinToString(":")

    private fun cachedBeacon(mac: String): KBeacon? = mgr?.getBeacon(formatMac(mac))

    /**
     * Obtain a KBeacon we can connect through.
     *
     * We do NOT depend on KBeaconsMgr's scan cache being populated — the
     * library's scanner has shown itself to be unreliable on some OEM
     * builds (Honor / Huawei in particular have returned no cache hits
     * even when the device is clearly advertising at high RSSI). Our UI
     * already runs Android's native BluetoothLeScanner in
     * [BleScanner], so the KBeaconsMgr cache is redundant work anyway.
     *
     * Instead we use the MAC to get a BluetoothDevice directly from the
     * system BluetoothAdapter (pure lookup, no I/O) and wire it into a
     * freshly-constructed KBeacon via the library's public
     * attach2Device API. The library's [KBeacon.connectEnhanced] then
     * has everything it needs — it just calls mBleDevice.connectGatt.
     *
     * We still check the manager's cache first so we reuse any instance
     * the library might have created on its own, preserving per-beacon
     * state across multiple connects.
     */
    private fun resolveBeacon(mac: String): KBeacon? {
        cachedBeacon(mac)?.let { return it }
        val formatted = formatMac(mac)
        val device = runCatching { btAdapter?.getRemoteDevice(formatted) }.getOrNull()
            ?: run {
                Timber.w("BluetoothAdapter.getRemoteDevice($formatted) returned null")
                return null
            }
        val beacon = KBeacon(formatted, context)
        beacon.attach2Device(device)
        return beacon
    }

    private fun disconnectQuietly(mac: String) {
        runCatching { cachedBeacon(mac)?.disconnect() }
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
        const val BATCH_SIZE = 200             // tune 100–500 based on stability
    }
}
