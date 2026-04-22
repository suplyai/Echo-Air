package app.suply.echoair.spike

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kkmcn.kbeaconlib2.KBCfgPackage.KBSensorType
import com.kkmcn.kbeaconlib2.KBConnPara
import com.kkmcn.kbeaconlib2.KBConnState
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordDataRsp
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBRecordHumidity
import com.kkmcn.kbeaconlib2.KBSensorHistoryData.KBSensorReadOption
import com.kkmcn.kbeaconlib2.KBeacon
import com.kkmcn.kbeaconlib2.KBeaconsMgr
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Debug-only spike to de-risk kbeaconlib2 against a real KKM S23H, with
 * parallel native-scanner diagnostics. Fires three probes:
 *
 *   1. KBeaconsMgr scan (library's own) with a KBeaconMgrDelegate that
 *      logs every discovery callback. Tells us whether the library is
 *      seeing anything at all.
 *   2. Native BluetoothLeScanner with a filter on the target MAC. Logs
 *      every matching ScanResult with raw service-data bytes. If this
 *      sees the device but the library doesn't, the library's
 *      parse/cache layer is the culprit.
 *   3. Direct connect via KBeacon(mac, ctx) + attach2Device, bypassing
 *      the scan cache entirely. This is also the path the shipped
 *      BleConnectionManager takes. Runs the full readSensorDataInfo +
 *      readSensorRecord flow.
 *
 * Symbols verified:
 *   KBSensorType.HTHumidity (package KBCfgPackage)
 *   beacon.commonCfg.isSupportHumiditySensor
 *   beacon.readSensorDataInfo / .readSensorRecord
 *   KBRecordDataRsp.{readDataRspList, readDataNextPos, INVALID_DATA_RECORD_POS}
 *   KBRecordHumidity.{utcTime, temperature, humidity}
 *   KBeacon(mac, ctx) + attach2Device public API
 */
object KBeaconLibSpike {

    data class Report(val log: List<String>, val succeeded: Boolean)

    @SuppressLint("MissingPermission")
    suspend fun run(context: Context, mac: String, password: String, batchSize: Int = 10): Report {
        val entries = mutableListOf<String>()
        fun log(line: String) {
            entries += line
            android.util.Log.i("EchoAirSpike", line)
        }

        // Permission sanity before anything else — otherwise the scan just silently returns nothing.
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            return Report(
                log = listOf("Missing permissions: $missing. Grant them in Settings and retry."),
                succeeded = false
            )
        }

        val btManager = context.getSystemService(BluetoothManager::class.java)
        val btAdapter: BluetoothAdapter? = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            return Report(
                log = listOf("Bluetooth adapter null or disabled. Enable Bluetooth and retry."),
                succeeded = false
            )
        }

        val formatted = if (mac.contains(":")) mac.uppercase()
                        else mac.uppercase().chunked(2).joinToString(":")

        log("=== Phase 1: KBeaconsMgr scan + KBeaconMgrDelegate (library path) ===")
        val mgr = KBeaconsMgr.sharedBeaconManager(context) ?: return Report(
            log = entries + "KBeaconsMgr.sharedBeaconManager returned null.",
            succeeded = false
        )
        val mgrDiscoveries = AtomicInteger(0)
        val mgrCbMacs = ConcurrentHashMap.newKeySet<String>()
        mgr.delegate = object : KBeaconsMgr.KBeaconMgrDelegate {
            override fun onBeaconDiscovered(beacons: Array<out KBeacon>) {
                mgrDiscoveries.incrementAndGet()
                beacons.forEach { b ->
                    mgrCbMacs.add(b.mac)
                    android.util.Log.i("EchoAirSpike", "onBeaconDiscovered mac=${b.mac} rssi=${b.rssi} name=${b.name}")
                }
            }
            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e("EchoAirSpike", "KBeaconsMgr scan failed: $errorCode")
            }
            override fun onCentralBleStateChang(state: Int) { /* unused */ }
        }
        val mgrStartResult = runCatching { mgr.startScanning() }.getOrElse { -1 }
        log("KBeaconsMgr.startScanning() returned $mgrStartResult")

        log("=== Phase 2: Native BluetoothLeScanner with MAC filter (ground truth) ===")
        val nativeHits = AtomicInteger(0)
        val nativeSampleBytes = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        val nativeCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                nativeHits.incrementAndGet()
                val rec = result.scanRecord
                if (nativeSampleBytes.get() == null && rec != null) {
                    nativeSampleBytes.set(rec.bytes)
                }
                android.util.Log.i(
                    "EchoAirSpike",
                    "NATIVE rssi=${result.rssi} name=${result.device.name ?: rec?.deviceName} " +
                        "services=${rec?.serviceUuids} sdEddy=${rec?.getServiceData(
                            android.os.ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
                        )?.toHexShort()}"
                )
            }
            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e("EchoAirSpike", "Native scan failed: $errorCode")
            }
        }
        val nativeScanner = btAdapter.bluetoothLeScanner
        val nativeFilter = ScanFilter.Builder().setDeviceAddress(formatted).build()
        val nativeSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()
        runCatching { nativeScanner.startScan(listOf(nativeFilter), nativeSettings, nativeCallback) }

        // Sample the two paths for 8 seconds with a progress heartbeat.
        val sampleWindowMs = 8_000L
        val deadline = System.currentTimeMillis() + sampleWindowMs
        var mgrCacheHit: KBeacon? = null
        while (System.currentTimeMillis() < deadline) {
            mgrCacheHit = mgr.getBeacon(formatted)
            if (mgrCacheHit != null) break
            delay(500)
        }
        runCatching { nativeScanner.stopScan(nativeCallback) }
        runCatching { mgr.stopScanning() }
        mgr.delegate = null

        log("--- Phase 1 & 2 results after ${sampleWindowMs}ms ---")
        log("  KBeaconsMgr onBeaconDiscovered fired ${mgrDiscoveries.get()}x  (seen MACs: ${mgrCbMacs.size})")
        log("  KBeaconsMgr.getBeacon($formatted) = ${if (mgrCacheHit == null) "NULL" else "HIT"}")
        log("  Native BluetoothLeScanner hits for $formatted = ${nativeHits.get()}")
        val sample = nativeSampleBytes.get()
        if (sample != null) {
            log("  Native raw adv bytes = ${sample.toHexShort()}")
        }

        // Interpretation.
        when {
            nativeHits.get() == 0 -> log(
                "→ Diagnosis: native scanner also sees nothing. Likely OEM scan-permission quirk " +
                "(Honor/Huawei sometimes require Location toggle ON even with neverForLocation). " +
                "Check that location services are on at the OS level."
            )
            mgrCacheHit == null && nativeHits.get() > 0 -> log(
                "→ Diagnosis: native sees the device but KBeaconsMgr does not cache it. The library's " +
                "own scan filter or parseAdvPacket is dropping this specific frame. The main app works " +
                "around this by bypassing the KBeaconsMgr cache and going via " +
                "BluetoothAdapter.getRemoteDevice + KBeacon.attach2Device directly."
            )
            mgrCacheHit != null -> log("→ Diagnosis: KBeaconsMgr cache works on this device. No workaround needed.")
        }

        log("=== Phase 3: Direct-resolve connect + full read flow ===")
        val device = runCatching { btAdapter.getRemoteDevice(formatted) }.getOrNull()
            ?: return Report(entries + "BluetoothAdapter.getRemoteDevice($formatted) returned null.", false)
        val beacon: KBeacon = mgrCacheHit ?: KBeacon(formatted, context).also { it.attach2Device(device) }
        log("Using KBeacon instance: ${if (mgrCacheHit == null) "freshly constructed + attach2Device" else "from KBeaconsMgr cache"}")

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
                beacon.connectEnhanced(password, 20_000, para) { _, state, nReason ->
                    when (state) {
                        KBConnState.Connected -> if (!cont.isCompleted) cont.resume(Unit)
                        KBConnState.Disconnected -> if (!cont.isCompleted)
                            cont.resumeWithException(RuntimeException("disconnected (reason=$nReason)"))
                        else -> Unit
                    }
                }
            }

            val common = beacon.commonCfg
            log("Connected. commonCfg model=${common?.model} hwRev=${common?.hardwareVersion}")

            val supportsHumidity = common?.isSupportHumiditySensor == true
            log("commonCfg.isSupportHumiditySensor = $supportsHumidity")
            log("KBSensorType.HTHumidity = ${KBSensorType.HTHumidity}")
            val sensorType = KBSensorType.HTHumidity

            log("readSensorDataInfo($sensorType) ...")
            val info = suspendCancellableCoroutine { cont ->
                beacon.readSensorDataInfo(sensorType) { success, rsp, error ->
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

            suspend fun readFrom(startPos: Long, label: String): Pair<List<Any>, Long> {
                log("readSensorRecord(start=$label, NormalOrder, n=$batchSize) ...")
                val rsp: KBRecordDataRsp = suspendCancellableCoroutine { cont ->
                    beacon.readSensorRecord(
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

            var (firstBatch, _) = readFrom(KBRecordDataRsp.INVALID_DATA_RECORD_POS,
                "INVALID_DATA_RECORD_POS (${KBRecordDataRsp.INVALID_DATA_RECORD_POS})")
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
                if (r == null) log("  record[$i] = ${raw.javaClass.simpleName} (not a KBRecordHumidity)")
                else log("  record[$i] utcTime=${r.utcTime} temperature=${r.temperature} humidity=${r.humidity}")
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
            runCatching { beacon.disconnect() }
        }
    }

    private fun requiredPermissions(): List<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ByteArray.toHexShort(limit: Int = 32): String {
        val take = take(limit)
        val hex = take.joinToString("") { "%02X".format(it) }
        return if (size > limit) "$hex… (${size}B)" else hex
    }
}
