package app.suply.echoair.ui.collection

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.suply.echoair.ble.CollectionOrchestrator
import app.suply.echoair.ble.service.CollectionScanService
import app.suply.echoair.data.ShipmentRepository
import app.suply.echoair.data.db.CachedShipment
import app.suply.echoair.data.db.CachedUnit
import app.suply.echoair.location.LocationCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionViewModel @Inject constructor(
    app: Application,
    private val repo: ShipmentRepository,
    private val orchestrator: CollectionOrchestrator,
    // Exposed to the Collection screen so the one-time LocationRationaleDialog
    // can read the acknowledgment state and write the opt-in choice. The
    // capture call itself happens inside the orchestrator.
    val locationCapture: LocationCapture
) : AndroidViewModel(app) {

    val state = orchestrator.state

    private val _shipment = MutableStateFlow<CachedShipment?>(null)
    val shipment: StateFlow<CachedShipment?> = _shipment.asStateFlow()

    /**
     * Multiple Package Shipment unit roster. Empty for legacy / single-unit
     * shipments — the Collection screen falls back to flat rendering in
     * that case. Sequence is server-defined (sequence_index ASC).
     */
    private val _units = MutableStateFlow<List<CachedUnit>>(emptyList())
    val units: StateFlow<List<CachedUnit>> = _units.asStateFlow()

    fun start(shipmentId: String) {
        viewModelScope.launch {
            val s = repo.getShipment(shipmentId) ?: return@launch
            _shipment.value = s
            _units.value = repo.unitsForShipment(shipmentId)
            val roster = repo.devicesForShipment(shipmentId)
            orchestrator.start(
                shipmentId,
                roster.map {
                    CollectionOrchestrator.ExpectedDevice(
                        deviceId = it.deviceId,
                        mac = it.mac,
                        unitId = it.unitId
                    )
                }
            )
            // Keep the process alive via foreground service.
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, CollectionScanService::class.java).apply {
                putExtra(CollectionScanService.EXTRA_SHIPMENT_ID, shipmentId)
            }
            ContextCompat.startForegroundService(ctx, intent)
        }
    }

    fun retry(deviceId: String) = orchestrator.retry(deviceId)

    fun markPartialAndStop() {
        orchestrator.markPartial()
        orchestrator.stop()
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, CollectionScanService::class.java))
    }

    override fun onCleared() {
        // Orchestrator keeps running if foreground service is alive; only stop
        // when user explicitly closes the shipment via markPartialAndStop.
        super.onCleared()
    }
}
