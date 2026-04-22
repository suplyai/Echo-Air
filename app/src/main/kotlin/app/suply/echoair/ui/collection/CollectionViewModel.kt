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
    private val orchestrator: CollectionOrchestrator
) : AndroidViewModel(app) {

    val state = orchestrator.state

    private val _shipment = MutableStateFlow<CachedShipment?>(null)
    val shipment: StateFlow<CachedShipment?> = _shipment.asStateFlow()

    fun start(shipmentId: String) {
        viewModelScope.launch {
            val s = repo.getShipment(shipmentId) ?: return@launch
            _shipment.value = s
            val expected = repo.devicesFor(shipmentId)
            // Snapshot the roster from cache for the orchestrator.
            val roster = repo.activeRosterDeviceIdsFor(shipmentId)
            orchestrator.start(
                shipmentId,
                roster.map { CollectionOrchestrator.ExpectedDevice(it.deviceId, it.mac) }
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
