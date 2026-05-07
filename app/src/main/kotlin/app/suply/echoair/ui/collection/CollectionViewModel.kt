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

    /**
     * Explicit session-end hook (v0.5.8). Routes both the all-collected
     * "Finish" tap and the close-without-remaining confirmation through
     * a single cleanup path: stamp finalizedAt on the orchestrator
     * state, cancel the scan job, and stop the foreground service so
     * the process is no longer pinned alive. Without this, the
     * @Singleton orchestrator + foreground service would keep the
     * previous session's device list in memory across reopen and the
     * user would land on stale state instead of a clean entry.
     */
    fun finalize() {
        orchestrator.finalize()
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, CollectionScanService::class.java))
    }

    /**
     * Close-with-partial-devices path: mark non-final devices as MISSING
     * (so the attestation pack records them as such), then run the
     * standard finalize cleanup. Same end-state as the explicit Finish
     * tap, just with a different device-state bookkeeping step first.
     */
    fun markPartialAndStop() {
        orchestrator.markPartial()
        finalize()
    }

    /**
     * Decide what to do when the Collection screen mounts for a given
     * shipmentId. The orchestrator may already have state for this
     * shipment from a previous session (it's a process-lifetime
     * @Singleton kept alive by the foreground service), and that state
     * may be:
     *   - currently in flight on a different shipmentId → ResumeFresh
     *   - explicitly finalized → GoHome (user already ended this
     *     shipment; reopening should land them clean)
     *   - all-devices-final and idle for >5 min → GoHome (implicit
     *     "user finished but never tapped the Finish button"; treat
     *     as finalised)
     *   - idle for >1 hour → GoHome regardless of progress (stale
     *     enough that resuming would be confusing)
     *   - otherwise → ResumeFresh (start or resume normally)
     *
     * The screen calls this in its LaunchedEffect(shipmentId) before
     * deciding between vm.start() and onClose(). Read-only — does not
     * mutate orchestrator state itself.
     */
    fun shouldResumeOrFinish(shipmentId: String): SessionAction {
        val current = orchestrator.state.value
        if (current.shipmentId != shipmentId) return SessionAction.ResumeFresh

        if (current.finalizedAt != null) return SessionAction.GoHome

        val now = System.currentTimeMillis()
        val idleMs = if (current.lastInteractionAt > 0) {
            now - current.lastInteractionAt
        } else 0L

        if (idleMs > STALE_SESSION_MS) return SessionAction.GoHome

        val allFinal = current.devices.isNotEmpty() &&
            current.devices.all {
                it.state == CollectionOrchestrator.DeviceState.COLLECTED ||
                    it.state == CollectionOrchestrator.DeviceState.ERROR ||
                    it.state == CollectionOrchestrator.DeviceState.MISSING
            }
        if (allFinal && idleMs > AUTO_FINALIZE_AFTER_MS) return SessionAction.GoHome

        return SessionAction.ResumeFresh
    }

    sealed interface SessionAction {
        /** Start (or no-op resume) the collection on this screen. */
        data object ResumeFresh : SessionAction

        /**
         * The previous session is finalized or stale enough that
         * reopening here would surface confusing old state. Call
         * [finalize] (idempotent) and pop back to home.
         */
        data object GoHome : SessionAction
    }

    override fun onCleared() {
        // Orchestrator keeps running if foreground service is alive; only stop
        // when user explicitly closes the shipment via markPartialAndStop or
        // finalize.
        super.onCleared()
    }

    private companion object {
        /** Idle ceiling: any orchestrator state untouched for this long
         *  is treated as stale on reopen and the user is routed home. */
        const val STALE_SESSION_MS: Long = 60 * 60 * 1000L          // 1 hour

        /** When all devices are in a final state but the user never
         *  tapped Finish, treat the session as implicitly finalized
         *  after this much idle time. Tighter than STALE_SESSION_MS
         *  because the only thing they'd resume to is a tappable
         *  Finish button on a screen full of completed cards. */
        const val AUTO_FINALIZE_AFTER_MS: Long = 5 * 60 * 1000L     // 5 minutes
    }
}
