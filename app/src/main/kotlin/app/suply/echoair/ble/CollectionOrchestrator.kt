package app.suply.echoair.ble

import app.suply.echoair.data.ShipmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val repo: ShipmentRepository
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
        val progress: Float = 0f,       // 0..1, only meaningful in SYNCING
        val recordCount: Int? = null,
        val tempMin: Double? = null,
        val tempMax: Double? = null,
        val error: String? = null,
        val alarm: Boolean = false
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
        _state.value = State(
            shipmentId = shipmentId,
            devices = expected.map { Device(deviceId = it.deviceId, mac = it.mac) },
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

        scanner.scan()
            .filter { beacon ->
                beacon.mac.uppercase() in expectedMacs || beacon.serial in expectedSerials
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
                val result = connection.downloadLog(beacon.mac) { progress ->
                    val frac = if (progress.total == 0) 0f else progress.current / progress.total.toFloat()
                    updateDevice(deviceId) { it.copy(progress = frac) }
                }
                val readings = result.records
                val resp = repo.submitRecords(
                    deviceId = deviceId,
                    records = readings,
                    deviceClockOffsetSeconds = result.deviceClockOffsetSeconds
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
                batteryMv = dev.batteryMv, alarm = dev.alarm,
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

    data class ExpectedDevice(val deviceId: String, val mac: String?)
}
