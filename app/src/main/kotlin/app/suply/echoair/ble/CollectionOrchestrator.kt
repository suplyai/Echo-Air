package app.suply.echoair.ble

import android.os.SystemClock
import app.suply.echoair.data.ShipmentRepository
import app.suply.echoair.location.LocationCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the collection screen. Given an expected roster, it:
 *  1. Starts a BLE scan filtered to the Eddystone UUID
 *  2. Filters detected beacons to the roster (by MAC or serial)
 *  3. For each in-range device, kicks off a GATT log download
 *  4. Persists records and submits to /api/echo-scan
 *  5. Emits per-device state for the UI
 *
 * The orchestrator is stateful but not tied to a ViewModel — the foreground
 * service also uses it so collection survives app backgrounding.
 */
@Singleton
class CollectionOrchestrator @Inject constructor(
    private val scanner: BleScanner,
    private val connection: BleConnectionManager,
    private val repo: ShipmentRepository,
    private val locationCapture: LocationCapture
) {
    enum class DeviceState { SEARCHING, IN_RANGE, SYNCING, COLLECTED, MISSING, ERROR }

    data class Device(
        val deviceId: String,
        val mac: String?,
        val state: DeviceState = DeviceState.SEARCHING,
        val rssi: Int? = null,
        val lastTemp: Double? = null,
        val lastHumidity: Double? = null,
        val batteryMv: Int? = null,
        val batteryPercent: Int? = null,
        val progress: Float = 0f,       // 0..1, only meaningful in SYNCING
        val recordCount: Int? = null,
        val tempMin: Double? = null,
        val tempMax: Double? = null,
        val error: String? = null,
        val alarm: Boolean = false,
        // Epoch-ms of when this device entered (or re-entered) SEARCHING.
        // Used by the UI to drive time-staged proximity hints when BLE
        // takes too long — survives rotation because the orchestrator is
        // a @Singleton, which the composable's ephemeral timers would not.
        val searchStartedAt: Long = 0L,
        /** Multiple Package Shipment attribution. References the unit this
         *  device belongs to; null on legacy / single-unit shipments and on
         *  the synthetic "Unattributed" edge case. The Collection screen
         *  uses this to group rows under unit headers when the shipment
         *  has more than one unit. */
        val unitId: String? = null
    ) {
        val collected: Boolean get() = state == DeviceState.COLLECTED
    }

    data class State(
        val shipmentId: String? = null,
        val devices: List<Device> = emptyList(),
        val allScanned: Boolean = false,
        val running: Boolean = false
    ) {
        val collectedCount: Int get() = devices.count { it.collected }
        val totalCount: Int get() = devices.size
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var scanJob: Job? = null
    private val connectionLock = Mutex()
    private val inFlight = mutableSetOf<String>()  // deviceIds currently downloading

    /**
     * Start collection for the shipment. Idempotent — calling twice with the
     * same shipment is a no-op.
     */
    fun start(shipmentId: String, expected: List<ExpectedDevice>) {
        if (_state.value.shipmentId == shipmentId && _state.value.running) return
        stop()
        val searchStart = System.currentTimeMillis()
        _state.value = State(
            shipmentId = shipmentId,
            devices = expected.map {
                Device(
                    deviceId = it.deviceId,
                    mac = it.mac,
                    searchStartedAt = searchStart,
                    unitId = it.unitId
                )
            },
            running = true
        )
        scanJob = scope.launch { runScan() }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(running = false) }
    }

    private suspend fun runScan() {
        val expectedMacs = _state.value.devices.mapNotNull { it.mac?.uppercase()?.replace(":", "") }.toSet()
        val expectedSerials = _state.value.devices.map { it.deviceId }.toSet()
        Timber.d(
            "Collection scan starting. Expected MACs=%s serials=%s",
            expectedMacs, expectedSerials
        )

        // Rate-limit log: one line per (MAC, decision) pair, so filter
        // mismatches surface without spamming logcat on every adv.
        val loggedFilterDecisions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        scanner.scan()
            .filter { beacon ->
                val macMatch = beacon.mac.uppercase() in expectedMacs
                val serialMatch = beacon.serial in expectedSerials
                val pass = macMatch || serialMatch
                val key = "${beacon.mac}|$pass"
                if (loggedFilterDecisions.add(key)) {
                    Timber.d(
                        "Filter %s: beacon mac=%s serial=%s (macMatch=%s serialMatch=%s)",
                        if (pass) "PASS" else "DROP",
                        beacon.mac, beacon.serial, macMatch, serialMatch
                    )
                }
                pass
            }
            .collect { beacon -> onBeacon(beacon) }
    }

    private fun onBeacon(beacon: KBeacon) {
        val deviceId = resolveDeviceId(beacon) ?: return
        updateDevice(deviceId) { d ->
            d.copy(
                state = when (d.state) {
                    DeviceState.SEARCHING -> DeviceState.IN_RANGE
                    DeviceState.COLLECTED, DeviceState.SYNCING -> d.state
                    else -> DeviceState.IN_RANGE
                },
                mac = d.mac ?: beacon.mac,
                rssi = beacon.rssi,
                lastTemp = beacon.temperatureC ?: d.lastTemp,
                lastHumidity = beacon.humidity ?: d.lastHumidity,
                batteryMv = beacon.batteryMv ?: d.batteryMv,
                batteryPercent = beacon.batteryPercent ?: d.batteryPercent,
                alarm = beacon.alarm || d.alarm
            )
        }
        triggerDownload(deviceId, beacon)
    }

    private fun triggerDownload(deviceId: String, beacon: KBeacon) {
        scope.launch {
            connectionLock.withLock {
                if (deviceId in inFlight) return@launch
                val current = _state.value.devices.firstOrNull { it.deviceId == deviceId } ?: return@launch
                if (current.state == DeviceState.COLLECTED) return@launch
                inFlight.add(deviceId)
            }
            try {
                updateDevice(deviceId) { it.copy(state = DeviceState.SYNCING, progress = 0f) }
                // === Timing instrumentation (v0.5.4) ===
                // Tag prefix "sync.timing.*" pairs with BleConnectionManager's
                // "ble.timing.*" so a single grep can pull the full per-device
                // breakdown from logcat. SystemClock.elapsedRealtime() is
                // monotonic; safe across NTP / user clock changes mid-sync.
                val syncStart = SystemClock.elapsedRealtime()
                Timber.i("sync.timing.start device=%s", deviceId)

                // Kick off location capture in parallel with the GATT
                // read. FusedLocation usually resolves in < 1s when a
                // recent fix exists, and the GATT log download takes
                // 3–10s — so by the time we're ready to POST, the
                // location is almost always already waiting. If it
                // isn't (opted out, permission missing, timeout,
                // Play Services absent), await() returns null and we
                // POST without it. See [LocationCapture] for the
                // privacy model.
                val locationStart = SystemClock.elapsedRealtime()
                val locationDeferred = scope.async { locationCapture.captureOnce() }

                val bleStart = SystemClock.elapsedRealtime()
                val result = connection.downloadLog(beacon.mac) { progress ->
                    val frac = if (progress.total == 0) 0f else progress.current / progress.total.toFloat()
                    updateDevice(deviceId) { it.copy(progress = frac) }
                }
                val bleElapsed = SystemClock.elapsedRealtime() - bleStart
                val readings = result.records
                Timber.i(
                    "sync.timing.ble device=%s elapsed_ms=%d records=%d",
                    deviceId, bleElapsed, readings.size
                )

                val location = locationDeferred.await()
                val locationElapsed = SystemClock.elapsedRealtime() - locationStart
                Timber.i(
                    "sync.timing.location device=%s elapsed_ms=%d attached=%b",
                    deviceId, locationElapsed, location != null
                )

                val submitStart = SystemClock.elapsedRealtime()
                val resp = repo.submitRecords(
                    deviceId = deviceId,
                    records = readings,
                    deviceClockOffsetSeconds = result.deviceClockOffsetSeconds,
                    location = location
                )
                val submitElapsed = SystemClock.elapsedRealtime() - submitStart
                Timber.i(
                    "sync.timing.submit device=%s elapsed_ms=%d outcome=%s",
                    deviceId, submitElapsed, if (resp != null) "ok" else "queued_or_failed"
                )

                val totalElapsed = SystemClock.elapsedRealtime() - syncStart
                Timber.i(
                    "sync.timing.summary device=%s records=%d total_ms=%d " +
                        "ble_ms=%d location_ms=%d submit_ms=%d",
                    deviceId, readings.size, totalElapsed,
                    bleElapsed, locationElapsed, submitElapsed
                )
                val tempMin = readings.minOfOrNull { it.temperature }
                val tempMax = readings.maxOfOrNull { it.temperature }
                updateDevice(deviceId) {
                    it.copy(
                        state = DeviceState.COLLECTED,
                        progress = 1f,
                        recordCount = readings.size,
                        tempMin = tempMin,
                        tempMax = tempMax
                    )
                }
                resp?.let { r ->
                    _state.update { it.copy(allScanned = r.allScanned) }
                }
            } catch (t: Throwable) {
                Timber.e(t, "collection failed for $deviceId")
                updateDevice(deviceId) {
                    it.copy(
                        state = DeviceState.ERROR,
                        error = t.message ?: "connection failed"
                    )
                }
            } finally {
                inFlight.remove(deviceId)
            }
        }
    }

    fun retry(deviceId: String) {
        val dev = _state.value.devices.firstOrNull { it.deviceId == deviceId } ?: return
        val mac = dev.mac ?: return
        updateDevice(deviceId) { it.copy(state = DeviceState.IN_RANGE, error = null) }
        triggerDownload(
            deviceId,
            KBeacon(
                serial = deviceId, mac = mac, name = "KBPRO_$deviceId",
                rssi = dev.rssi ?: -70,
                temperatureC = dev.lastTemp, humidity = dev.lastHumidity,
                batteryMv = dev.batteryMv, batteryPercent = dev.batteryPercent,
                alarm = dev.alarm,
                recordCount = null, seenAt = System.currentTimeMillis()
            )
        )
    }

    /** Mark still-searching devices as missing after the close-shipment confirmation. */
    fun markPartial() {
        _state.update { s ->
            s.copy(
                devices = s.devices.map {
                    if (it.state == DeviceState.SEARCHING || it.state == DeviceState.IN_RANGE)
                        it.copy(state = DeviceState.MISSING)
                    else it
                }
            )
        }
    }

    private fun resolveDeviceId(beacon: KBeacon): String? {
        val macUpper = beacon.mac.uppercase()
        return _state.value.devices.firstOrNull {
            it.mac?.uppercase()?.replace(":", "") == macUpper || it.deviceId == beacon.serial
        }?.deviceId
    }

    private fun updateDevice(deviceId: String, transform: (Device) -> Device) {
        _state.update { s ->
            s.copy(devices = s.devices.map { if (it.deviceId == deviceId) transform(it) else it })
        }
    }

    data class ExpectedDevice(val deviceId: String, val mac: String?, val unitId: String? = null)
}
