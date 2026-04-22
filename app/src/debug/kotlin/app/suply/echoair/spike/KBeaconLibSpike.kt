package app.suply.echoair.spike

import android.content.Context
import com.kkmcn.kbeaconlib2.KBCfgPackage.KBSensorType
import com.kkmcn.kbeaconlib2.KBConnPara
import com.kkmcn.kbeaconlib2.KBConnState
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordDataRsp
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordHumidity
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorReadOption
import com.kkmcn.kbeaconlib2.KBeaconsMgr
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Debug-only spike to de-risk the kbeaconlib2 symbols against a real KKM S23H.
 * The shipped BleConnectionManager uses the exact same flow; if any symbol
 * here doesn't resolve or behaves differently, the same change needs to land
 * in BleConnectionManager.
 *
 * Symbols this spike exercises (verify against KKM KBeaconProDemo_Android §4.3.6):
 *   - KBSensorType.HTHumidity                  (package KBCfgPackage)
 *   - beacon.commonCfg.isSupportHumiditySensor
 *   - beacon.readSensorDataInfo(sensorType, callback)
 *       returns KBRecordInfoRsp { sensorType, totalRecordNumber,
 *                                 unreadRecordNumber, readInfoUtcSeconds }
 *   - beacon.readSensorRecord(sensorType, startPos:Long,
 *                             KBSensorReadOption.NormalOrder, maxRecords,
 *                             callback)
 *       returns KBRecordDataRsp { readDataRspList, readDataNextPos, sensorType }
 *   - KBRecordHumidity { utcTime:long, temperature:float, humidity:float }
 *   - KBRecordDataRsp.INVALID_DATA_RECORD_POS == 4294967295L
 */
object KBeaconLibSpike {

    data class Report(val log: List<String>, val succeeded: Boolean)

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

        // Prime the manager's cache — getBeacon only returns from KBeaconsMgr's own scanner.
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
                beacon!!.connectEnhanced(password, 20_000, para) { _, state, nReason ->
                    when (state) {
                        KBConnState.Connected -> if (!cont.isCompleted) cont.resume(Unit)
                        KBConnState.Disconnected -> if (!cont.isCompleted)
                            cont.resumeWithException(RuntimeException("disconnected (reason=$nReason)"))
                        else -> Unit
                    }
                }
            }

            val common = beacon!!.commonCfg
            log("Connected. commonCfg model=${common?.model} hwRev=${common?.hardwareVersion}")

            val supportsHumidity = common?.isSupportHumiditySensor == true
            log("commonCfg.isSupportHumiditySensor = $supportsHumidity")
            log("KBSensorType.HTHumidity = ${KBSensorType.HTHumidity}")

            val sensorType = KBSensorType.HTHumidity

            // --- readSensorDataInfo ---
            log("readSensorDataInfo($sensorType) ...")
            val info = suspendCancellableCoroutine { cont ->
                beacon!!.readSensorDataInfo(sensorType) { success, rsp, error ->
                    if (success && rsp != null) cont.resume(rsp)
                    else cont.resumeWithException(error ?: RuntimeException("readSensorDataInfo failed"))
                }
            }
            val total = info.totalRecordNumber ?: 0
            val deviceUtc = info.readInfoUtcSeconds ?: 0L
            val phoneUtc = System.currentTimeMillis() / 1000
            log("  totalRecordNumber  = $total")
            log("  unreadRecordNumber = ${info.unreadRecordNumber}")
            log("  readInfoUtcSeconds = $deviceUtc  (device UTC)")
            log("  phone UTC          = $phoneUtc")
            log("  clock offset (phone − device) = ${phoneUtc - deviceUtc}s")

            // --- readSensorRecord: INVALID_DATA_RECORD_POS, NormalOrder, then optional 0L fallback ---
            suspend fun readFrom(startPos: Long, label: String): Pair<List<Any>, Long> {
                log("readSensorRecord(start=$label, NormalOrder, n=$batchSize) ...")
                val rsp: KBRecordDataRsp = suspendCancellableCoroutine { cont ->
                    beacon!!.readSensorRecord(
                        sensorType, startPos, KBSensorReadOption.NormalOrder, batchSize
                    ) { success, r, error ->
                        if (success && r != null) cont.resume(r)
                        else cont.resumeWithException(error ?: RuntimeException("readSensorRecord failed"))
                    }
                }
                val list: List<Any> = rsp.readDataRspList?.toList() ?: emptyList()
                log("  batch size   = ${list.size}")
                log("  nextPos      = ${rsp.readDataNextPos}")
                return list to (rsp.readDataNextPos ?: KBRecordDataRsp.INVALID_DATA_RECORD_POS)
            }

            var (firstBatch, _) = readFrom(KBRecordDataRsp.INVALID_DATA_RECORD_POS, "INVALID_DATA_RECORD_POS (${KBRecordDataRsp.INVALID_DATA_RECORD_POS})")
            var usedStartPos = "INVALID_DATA_RECORD_POS"
            if (firstBatch.isEmpty() && total > 0) {
                log("  INVALID_DATA_RECORD_POS returned empty while total=$total; retrying from 0 ...")
                val fallback = readFrom(0L, "0L")
                firstBatch = fallback.first
                if (firstBatch.isNotEmpty()) {
                    usedStartPos = "0"
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
                    total == 0 -> "SPIKE PASSED — symbols resolved; device has no records yet (freshly activated)."
                    else -> "SPIKE PARTIAL — symbols resolved but no records returned."
                }
            )
            Report(log = entries.toList(), succeeded = true)
        } catch (t: Throwable) {
            log("SPIKE FAILED: ${t.javaClass.simpleName}: ${t.message}")
            Report(log = entries.toList(), succeeded = false)
        } finally {
            runCatching { beacon?.disconnect() }
        }
    }
}
