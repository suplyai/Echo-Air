package app.suply.echoair.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory sync timing recorder for the v0.5.4+ diagnostic surface.
 *
 * The Timber.i timing logs already stream to logcat, but the pilot-
 * device path doesn't have adb attached. So in addition to the logs,
 * each sync stage writes a structured event here, keyed by deviceId,
 * and the Collection screen surfaces the assembled breakdown in a
 * debug-only dialog so we can screenshot it.
 *
 * Builder-of-record pattern: [start] opens a row in [pending], each
 * stage updates that row, [finish] commits the assembled
 * [DeviceSyncTiming] into the published StateFlow. Concurrent-safe —
 * BLE work runs on Dispatchers.Main (kbeaconlib2 Handler quirk) while
 * the orchestrator and repo are on Default/IO; ConcurrentHashMap +
 * StateFlow.update covers cross-thread access.
 *
 * Lives only as long as the process; cleared on app death. Acceptable
 * because this is a transient diagnostic surface — once we've used it
 * to identify the bottleneck, the surface and the recorder come out
 * together.
 */
@Singleton
class SyncTimingRecorder @Inject constructor() {

    private val _records = MutableStateFlow<Map<String, DeviceSyncTiming>>(emptyMap())
    val records: StateFlow<Map<String, DeviceSyncTiming>> = _records.asStateFlow()

    private val pending = ConcurrentHashMap<String, Builder>()

    fun start(deviceId: String, mac: String, bluetoothEnabled: Boolean) {
        pending[deviceId] = Builder(
            deviceId = deviceId,
            mac = mac,
            bluetoothEnabledAtStart = bluetoothEnabled
        )
    }

    fun connect(deviceId: String, elapsedMs: Long, mtu: Int?) {
        pending[deviceId]?.let {
            it.connectMs = elapsedMs
            it.negotiatedMtu = mtu
        }
    }

    fun info(deviceId: String, elapsedMs: Long, totalRecords: Int) {
        pending[deviceId]?.let {
            it.infoMs = elapsedMs
            it.totalRecords = totalRecords
        }
    }

    fun batch(deviceId: String, idx: Int, elapsedMs: Long, records: Int) {
        pending[deviceId]?.batches?.add(BatchTiming(idx = idx, elapsedMs = elapsedMs, records = records))
    }

    fun bleSummary(deviceId: String, recordsCollected: Int, totalMs: Long) {
        pending[deviceId]?.let {
            it.recordsCollected = recordsCollected
            it.bleTotalMs = totalMs
        }
    }

    fun location(deviceId: String, elapsedMs: Long, attached: Boolean) {
        pending[deviceId]?.let {
            it.locationMs = elapsedMs
            it.locationAttached = attached
        }
    }

    fun persist(deviceId: String, elapsedMs: Long) {
        pending[deviceId]?.persistMs = elapsedMs
    }

    fun upload(
        deviceId: String,
        elapsedMs: Long,
        outcome: String,
        errorClass: String? = null,
        httpCode: Int? = null,
        errorMessage: String? = null,
        errorBody: String? = null
    ) {
        pending[deviceId]?.let {
            it.uploadMs = elapsedMs
            it.uploadOutcome = outcome
            it.uploadErrorClass = errorClass
            it.uploadHttpCode = httpCode
            it.uploadErrorMessage = errorMessage
            it.uploadErrorBody = errorBody
        }
    }

    fun finish(deviceId: String, totalMs: Long) {
        val b = pending.remove(deviceId) ?: return
        val timing = DeviceSyncTiming(
            deviceId = b.deviceId,
            mac = b.mac,
            bluetoothEnabledAtStart = b.bluetoothEnabledAtStart,
            negotiatedMtu = b.negotiatedMtu,
            totalRecords = b.totalRecords,
            recordsCollected = b.recordsCollected,
            connectMs = b.connectMs,
            infoMs = b.infoMs,
            batches = b.batches.toList(),
            bleTotalMs = b.bleTotalMs,
            locationMs = b.locationMs,
            locationAttached = b.locationAttached,
            persistMs = b.persistMs,
            uploadMs = b.uploadMs,
            uploadOutcome = b.uploadOutcome,
            uploadErrorClass = b.uploadErrorClass,
            uploadHttpCode = b.uploadHttpCode,
            uploadErrorMessage = b.uploadErrorMessage,
            uploadErrorBody = b.uploadErrorBody,
            totalMs = totalMs
        )
        _records.update { it + (deviceId to timing) }
    }

    private class Builder(
        val deviceId: String,
        val mac: String,
        val bluetoothEnabledAtStart: Boolean,
        var negotiatedMtu: Int? = null,
        var totalRecords: Int = 0,
        var recordsCollected: Int = 0,
        var connectMs: Long = 0,
        var infoMs: Long = 0,
        val batches: MutableList<BatchTiming> = mutableListOf(),
        var bleTotalMs: Long = 0,
        var locationMs: Long? = null,
        var locationAttached: Boolean = false,
        var persistMs: Long? = null,
        var uploadMs: Long? = null,
        var uploadOutcome: String? = null,
        var uploadErrorClass: String? = null,
        var uploadHttpCode: Int? = null,
        var uploadErrorMessage: String? = null,
        var uploadErrorBody: String? = null
    )
}

/**
 * Stage-by-stage breakdown of one device sync. All elapsed times are
 * in milliseconds. Persist/upload/location are nullable because the
 * sync may complete without ever reaching them (e.g. BLE error path).
 */
data class DeviceSyncTiming(
    val deviceId: String,
    val mac: String,
    /**
     * Adapter state at the moment the orchestrator entered its
     * BLE-work block (v0.5.9). Should always be true in practice —
     * the screen-level gate prevents vm.start from running otherwise
     * — but useful as a sanity check in field captures and a
     * regression sentinel if the gate were ever bypassed.
     */
    val bluetoothEnabledAtStart: Boolean = true,
    val negotiatedMtu: Int?,
    val totalRecords: Int,
    val recordsCollected: Int,
    val connectMs: Long,
    val infoMs: Long,
    val batches: List<BatchTiming>,
    val bleTotalMs: Long,
    val locationMs: Long?,
    val locationAttached: Boolean,
    val persistMs: Long?,
    val uploadMs: Long?,
    val uploadOutcome: String?,
    /** Throwable simple class name when [uploadOutcome] is "queued", e.g.
     *  "SocketTimeoutException", "HttpException", "UnknownHostException".
     *  Identifies network-level vs server-acknowledged failures at a glance. */
    val uploadErrorClass: String? = null,
    /** HTTP status when the failure was a Retrofit HttpException — 504/502
     *  signals a gateway / load-balancer timeout (server acknowledged the
     *  request but couldn't respond in time); 5xx signals server crash;
     *  4xx signals a client-side problem. Null for non-HTTP failures. */
    val uploadHttpCode: Int? = null,
    /** Throwable.message — short, often the most diagnostic line. */
    val uploadErrorMessage: String? = null,
    /** Server-returned response body (capped at 2 KB by the catch). For
     *  HttpException only — surface a structured error payload (validation
     *  detail, trace ID, etc.) so the field engineer doesn't have to wait
     *  on backend logs to identify the failure mode. */
    val uploadErrorBody: String? = null,
    val totalMs: Long
) {
    /** Aggregate records-per-second across the batched read phase. */
    val bleRecordsPerSecond: Double
        get() = if (bleTotalMs > 0) recordsCollected * 1000.0 / bleTotalMs else 0.0

    /**
     * Plain-text dump suitable for clipboard / paste. Same key=value
     * shape as the Timber logs so the field engineer's screenshot can
     * be transcribed (or pasted) into a message and grep'd directly.
     */
    fun toPlainText(): String = buildString {
        appendLine("Sync timing — device $deviceId ($mac)")
        appendLine("Total: ${totalMs}ms across $recordsCollected/$totalRecords records")
        appendLine("BT at start: $bluetoothEnabledAtStart")
        appendLine()
        appendLine("BLE — total ${bleTotalMs}ms (${"%.1f".format(bleRecordsPerSecond)} rec/s)")
        appendLine("  connect      ${connectMs}ms  mtu=${negotiatedMtu ?: "?"}")
        appendLine("  info         ${infoMs}ms")
        batches.forEach { b ->
            val rps = if (b.elapsedMs > 0) b.records * 1000.0 / b.elapsedMs else 0.0
            appendLine("  batch[${b.idx}]    ${b.elapsedMs}ms  ${b.records} rec  (${"%.1f".format(rps)} rec/s)")
        }
        appendLine()
        appendLine("Location — ${locationMs ?: "—"}ms  attached=$locationAttached")
        appendLine()
        appendLine("Submit")
        appendLine("  persist      ${persistMs ?: "—"}ms")
        appendLine("  upload       ${uploadMs ?: "—"}ms  outcome=${uploadOutcome ?: "—"}")
        if (uploadErrorClass != null) {
            val codeStr = uploadHttpCode?.let { " http=$it" } ?: ""
            val msgStr = uploadErrorMessage?.let { "  msg=\"$it\"" } ?: ""
            appendLine("    error_class=$uploadErrorClass$codeStr$msgStr")
            uploadErrorBody?.takeIf { it.isNotBlank() }?.let { body ->
                appendLine("    body=$body")
            }
        }
        appendLine()
        appendLine("Notes:")
        appendLine("  • kbeaconlib2 MTU target = 251 (BLE 5.x max = 517)")
        appendLine("  • CONNECTION_PRIORITY_HIGH not requested — running at BALANCED default")
    }
}

data class BatchTiming(val idx: Int, val elapsedMs: Long, val records: Int)
