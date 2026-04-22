package app.suply.echoair.spike

import android.content.Context
import com.kkmcn.kbeaconlib2.KBConnPara
import com.kkmcn.kbeaconlib2.KBConnState
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordHumidity
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorReadOption
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorType
import com.kkmcn.kbeaconlib2.KBeaconsMgr
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Debug-only spike to de-risk the kbeaconlib2 symbols this week against a
 * real KKM S23H. The shipped BleConnectionManager uses the exact same flow;
 * if any symbol here doesn't resolve or behaves differently, the same change
 * needs to land in BleConnectionManager.
 *
 * Symbols this spike exercises (verify against KKM KBeaconProDemo_Android §4.3.6):
 *   - KBSensorType.HTHumidity
 *   - mBeacon.commonCfg.isSupportHumiditySensor
 *   - mBeacon.readSensorDataInfo(sensorType, callback)
 *       return: totalRecordNumber, unreadRecordNumber, readInfoUtcSeconds
 *   - mBeacon.readSensorRecord(sensorType, startPos, KBSensorReadOption.NormalOrder,
 *                              maxRecordNum, callback)
 *       return: readDataRsp (List<KBRecordHumidity>), readDataNextPos
 *   - KBRecordHumidity.utcTime, .temperature, .humidity
 *
 * Usage: SpikeActivity takes a MAC in the format "BC:57:29:1C:D6:A6" and the
 * device password (default "0000000000000000"), runs this routine, and dumps
 * every verified field to the on-screen log plus Logcat tag "EchoAirSpike".
 */
object KBeaconLibSpike {

    data class Report(
        val log: List<String>,
        val succeeded: Boolean
    )

    suspend fun run(context: Context, mac: String, password: String, batchSize: Int = 10): Report {
        val entries = mutableListOf<String>()
        fun log(line: String) {
            entries += line
            android.util.Log.i("EchoAirSpike", line)
        }

        val mgr = KBeaconsMgr.sharedBeaconManager(context) ?: return Report(
            log = listOf("KBeaconsMgr.sharedBeaconManager returned null — BLE unavailable?"),
            succeeded = false
        )
        val formatted = if (mac.contains(":")) mac.uppercase()
                        else mac.uppercase().chunked(2).joinToString(":")

        // Prime the manager's cache by running a short KBeaconsMgr scan
        // so getBeacon(mac) resolves. KBeaconsMgr.getBeacon only returns
        // beacons the library's own scanner has already seen.
        log("WARMUP: KBeaconsMgr.startScanning() for up to 8s waiting for $formatted ...")
        runCatching { mgr.startScanning() }
        var beacon = mgr.getBeacon(formatted)
        val deadline = System.currentTimeMillis() + 8_000
        while (beacon == null && System.currentTimeMillis() < deadline) {
            delay(250)
            beacon = mgr.getBeacon(formatted)
        }
        runCatching { mgr.stopScanning() }
        if (beacon == null) {
            return Report(
                log = entries + "No KBeacon for $formatted after 8s scan. Confirm the device is powered and advertising.",
                succeeded = false
            )
        }
        log("WARMUP: resolved KBeacon, name=${beacon.name} rssi=${beacon.rssi}")

        return try {
            log("CONNECT: $formatted")
            val para = KBConnPara().apply {
                syncUtcTime = false
                readCommPara = true
                readSensorPara = true
                readTriggerPara = false
                readSlotPara = false
            }
            suspendCancellableCoroutine<Unit> { cont ->
                beacon.connect(password, 20_000, para) { _, state, ex ->
                    when (state) {
                        KBConnState.Connected -> if (!cont.isCompleted) cont.resume(Unit)
                        KBConnState.Disconnected -> if (!cont.isCompleted)
                            cont.resumeWithException(ex ?: RuntimeException("disconnected"))
                        else -> Unit
                    }
                }
            }
            log("Connected. DeviceInfo model=${beacon.model} hwRev=${beacon.hardwareVersion} fwRev=${beacon.firmwareVersion}")

            // --- commonCfg.isSupportHumiditySensor ---
            val common = beacon.commonCfg
            val supportsHumidity = common?.isSupportHumiditySensor == true
            log("commonCfg.isSupportHumiditySensor = $supportsHumidity")

            val sensorType = if (supportsHumidity) KBSensorType.HTHumidity else KBSensorType.Temperature
            log("KBSensorType selected = $sensorType (HTHumidity=${KBSensorType.HTHumidity}, Temperature=${KBSensorType.Temperature})")

            // --- readSensorDataInfo ---
            log("readSensorDataInfo($sensorType) ...")
            val info = suspendCancellableCoroutine { cont ->
                beacon.sensorHistoryData!!.readSensorDataInfo(sensorType) { success, ex, dataInfo ->
                    if (success && dataInfo != null) cont.resume(dataInfo)
                    else cont.resumeWithException(ex ?: RuntimeException("readSensorDataInfo failed"))
                }
            }
            val phoneUtc = System.currentTimeMillis() / 1000
            log("  totalRecordNumber    = ${info.totalRecordNumber}")
            log("  unreadRecordNumber   = ${info.unreadRecordNumber}")
            log("  readInfoUtcSeconds   = ${info.readInfoUtcSeconds}  (device UTC)")
            log("  phone UTC            = $phoneUtc")
            log("  clock offset (phone − device) = ${phoneUtc - info.readInfoUtcSeconds}s")

            // --- readSensorRecord: first batch, NormalOrder, from INVALID_DATA_RECORD_POS ---
            // KKM's docs don't explicitly specify first-call behaviour. We try
            // -1 (INVALID_DATA_RECORD_POS) first; if that returns empty while
            // totalRecordNumber > 0, we retry from 0 and log which one works
            // so the team can update the main code if needed.
            suspend fun readFrom(startPos: Int, label: String): Pair<List<Any>, Int> {
                log("readSensorRecord(start=$label, NormalOrder, n=$batchSize) ...")
                val rsp = suspendCancellableCoroutine { cont ->
                    beacon.sensorHistoryData!!.readSensorRecord(
                        sensorType, startPos, KBSensorReadOption.NormalOrder, batchSize
                    ) { success, ex, r ->
                        if (success && r != null) cont.resume(r)
                        else cont.resumeWithException(ex ?: RuntimeException("readSensorRecord failed"))
                    }
                }
                val list = rsp.readDataRsp ?: emptyList<Any>()
                log("  batch size   = ${list.size}")
                log("  nextPos      = ${rsp.readDataNextPos}")
                return list to rsp.readDataNextPos
            }

            var (firstBatch, _) = readFrom(-1, "INVALID_DATA_RECORD_POS (-1)")
            var usedStartPos = -1
            if (firstBatch.isEmpty() && info.totalRecordNumber > 0) {
                log("  -1 returned empty while total=${info.totalRecordNumber}; retrying from 0 ...")
                val fallback = readFrom(0, "0")
                firstBatch = fallback.first
                if (firstBatch.isNotEmpty()) {
                    usedStartPos = 0
                    log("  → FALLBACK SUCCEEDED at startPos=0. UPDATE MAIN CODE to use 0 instead of INVALID_DATA_RECORD_POS.")
                } else {
                    log("  → FALLBACK ALSO EMPTY. Check that records exist on this device and sensor variant matches.")
                }
            }
            log("  startPos that returned records = $usedStartPos")

            firstBatch.take(3).forEachIndexed { i, raw ->
                val r = raw as? KBRecordHumidity
                if (r == null) {
                    log("  record[$i] = ${raw.javaClass.simpleName} (not a KBRecordHumidity — check sensor variant)")
                } else {
                    log("  record[$i] utcTime=${r.utcTime} temperature=${r.temperature} humidity=${r.humidity}")
                }
            }

            log(
                when {
                    firstBatch.isNotEmpty() -> "SPIKE PASSED — all expected symbols resolved."
                    info.totalRecordNumber == 0 -> "SPIKE PASSED — symbols resolved; device has no records yet (freshly activated)."
                    else -> "SPIKE PARTIAL — symbols resolved but no records returned."
                }
            )
            Report(log = entries.toList(), succeeded = true)
        } catch (t: Throwable) {
            log("SPIKE FAILED: ${t.javaClass.simpleName}: ${t.message}")
            Report(log = entries.toList(), succeeded = false)
        } finally {
            runCatching { beacon.disconnect() }
        }
    }
}
