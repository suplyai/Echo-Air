package app.suply.echoair.data

import android.content.Context
import androidx.work.WorkManager
import app.suply.echoair.data.api.EchoScanRequest
import app.suply.echoair.data.api.EchoScanResponse
import app.suply.echoair.data.api.LocationDto
import app.suply.echoair.data.api.ReadingDto
import app.suply.echoair.data.api.ShipmentDeviceDto
import app.suply.echoair.data.api.ShipmentDto
import app.suply.echoair.data.api.SuplyApi
import app.suply.echoair.data.api.VisionRequest
import app.suply.echoair.data.api.VisionResponse
import app.suply.echoair.data.db.CachedDevice
import app.suply.echoair.data.db.CachedShipment
import app.suply.echoair.data.db.CachedUnit
import app.suply.echoair.data.db.DeviceDao
import app.suply.echoair.data.db.PendingUpload
import app.suply.echoair.data.db.PendingUploadDao
import app.suply.echoair.data.db.RecordDao
import app.suply.echoair.data.db.ShipmentDao
import app.suply.echoair.data.db.TemperatureRecord
import app.suply.echoair.data.db.UnitDao
import app.suply.echoair.work.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for shipment + device + telemetry operations. Everything
 * is persisted locally first so the app works offline; the API is a best-effort
 * mirror.
 */
@Singleton
class ShipmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SuplyApi,
    private val shipmentDao: ShipmentDao,
    private val deviceDao: DeviceDao,
    private val unitDao: UnitDao,
    private val recordDao: RecordDao,
    private val uploadDao: PendingUploadDao,
    private val json: Json
) {

    suspend fun lookupDevice(identifier: String): ShipmentDto? {
        val resp = api.lookupDevice(identifier, includeShipment = true)
        val raw = resp.shipment ?: return null.also {
            Timber.i("Device lookup: shipment sub-object absent for identifier=%s", identifier)
        }
        // Guardrail for a real pilot symptom: the /api/devices/lookup
        // response's embedded shipment sub-object sometimes arrives with an
        // empty devices[] array even when we know a device is attached (we
        // literally just looked it up by that device's identifier). If the
        // backend dropped the list, synthesize it from the top-level
        // response fields — otherwise the confirmation sheet renders the
        // misleading "No devices expected". Emit one Timber line per
        // observed shape so we can push the backend to populate devices[]
        // consistently; then delete this workaround.
        val shipment = if (raw.devices.isEmpty()) {
            Timber.w(
                "Device lookup: shipment %s came back with devices[]=[]; synthesising entry for %s",
                raw.id, resp.deviceId
            )
            raw.copy(
                devices = listOf(
                    ShipmentDeviceDto(
                        deviceId = resp.deviceId,
                        mac = resp.mac,
                        serial = resp.serial,
                        model = resp.model,
                        status = "assigned"
                    )
                )
            )
        } else {
            Timber.i(
                "Device lookup: shipment %s resolved with %d device(s) in sub-object",
                raw.id, raw.devices.size
            )
            raw
        }
        cache(shipment)
        return shipment
    }

    /**
     * Manual-entry path for the "Enter AWB" fallback. Reuses the vision
     * endpoint with the [VisionRequest.awbNumber] field set, so the
     * response shape is identical to a vision-AI capture.
     */
    suspend fun identifyFromAwb(awbNumber: String): VisionResponse {
        val resp = api.identifyShipment(VisionRequest(awbNumber = awbNumber.trim()))
        resp.shipment?.let { cache(it) }
        return resp
    }

    suspend fun searchByAwb(query: String): List<ShipmentDto> {
        val resp = api.listShipments(status = "in_transit", search = query)
        resp.shipments.forEach { cache(it) }
        return resp.shipments
    }

    suspend fun getShipment(id: String): CachedShipment? {
        val cached = shipmentDao.byId(id)
        if (cached != null) return cached
        return try {
            cache(api.shipment(id))
            shipmentDao.byId(id)
        } catch (t: Throwable) {
            Timber.w(t, "getShipment($id) failed")
            null
        }
    }

    /**
     * Persists records locally and attempts to submit them. Returns the server
     * response on success, or null if we're offline (the records are queued).
     *
     * [deviceClockOffsetSeconds] is phone UTC minus device UTC at the start
     * of readout, captured from readSensorDataInfo.readInfoUtcSeconds. It is
     * forwarded as-is; timestamps on [records] are the raw device values.
     */
    suspend fun submitRecords(
        deviceId: String,
        records: List<ReadingDto>,
        deviceClockOffsetSeconds: Long? = null,
        location: LocationDto? = null
    ): EchoScanResponse? {
        val rows = records.map {
            TemperatureRecord(
                deviceId = deviceId,
                timestamp = it.timestamp,
                temperature = it.temperature,
                humidity = it.humidity
            )
        }
        recordDao.insertAll(rows)

        val request = EchoScanRequest(
            deviceId = deviceId,
            temperatureRecords = records,
            deviceClockOffsetSeconds = deviceClockOffsetSeconds,
            location = location
        )
        return try {
            val resp = api.echoScan(request)
            recordDao.markUploaded(deviceId)
            deviceDao.updateStatus(
                id = deviceId,
                status = "scanned",
                seenAt = System.currentTimeMillis()
            )
            resp
        } catch (t: Throwable) {
            Timber.w(t, "echoScan failed; queuing for retry")
            uploadDao.enqueue(
                PendingUpload(
                    deviceId = deviceId,
                    payloadJson = json.encodeToString(EchoScanRequest.serializer(), request),
                    createdAt = System.currentTimeMillis()
                )
            )
            UploadWorker.enqueue(WorkManager.getInstance(context))
            null
        }
    }

    suspend fun devicesForShipment(shipmentId: String): List<CachedDevice> =
        deviceDao.forShipmentOnce(shipmentId)

    /**
     * Multi-unit / Multiple Package Shipment (MPS) breakdown for a given
     * shipment. Returns the cached unit list ordered by sequence_index;
     * empty list for legacy single-unit cached rows or for shipments with
     * no MPS data on the wire. The Collection screen uses this to decide
     * between grouped (units > 1) and flat (units ≤ 1) rendering.
     */
    suspend fun unitsForShipment(shipmentId: String): List<CachedUnit> =
        unitDao.forShipmentOnce(shipmentId)

    private suspend fun cache(s: ShipmentDto) {
        shipmentDao.upsert(
            CachedShipment(
                id = s.id,
                awbNumber = s.airwayBillNumber,
                originIata = s.airOriginIata,
                destIata = s.airDestIata,
                originCity = s.airOriginCity,
                destCity = s.airDestCity,
                commodityName = s.cargoProfile?.name,
                commodityMinTemp = s.cargoProfile?.minTemp,
                commodityMaxTemp = s.cargoProfile?.maxTemp,
                status = s.status,
                updatedAt = System.currentTimeMillis()
            )
        )
        deviceDao.upsertAll(
            s.devices.map {
                CachedDevice(
                    deviceId = it.deviceId,
                    mac = it.mac?.uppercase()?.replace(":", ""),
                    shipmentId = s.id,
                    status = it.status,
                    lastSeenAt = parseIsoEpochMillis(it.scannedAt),
                    unitId = it.unitId
                )
            }
        )
        // Replace the unit roster wholesale — the consignee endpoint
        // always returns the full list, so partial-update semantics
        // would only invite drift between dashboard edits and the cache.
        // No-op when units[] is empty (legacy / single-unit responses).
        unitDao.deleteForShipment(s.id)
        if (s.units.isNotEmpty()) {
            unitDao.upsertAll(
                s.units.map { u ->
                    CachedUnit(
                        id = u.id,
                        shipmentId = s.id,
                        label = u.label,
                        position = u.position,
                        sequenceIndex = u.sequenceIndex,
                        commodityOverride = u.commodityOverride
                    )
                }
            )
        }
    }

    /**
     * Convert an ISO-8601 instant (e.g. "2026-04-23T15:41:29.900Z") to epoch
     * millis for the Long-typed Room column. Returns null on missing or
     * malformed input rather than throwing — the backend is allowed to emit
     * future date formats we don't yet parse, and we'd rather lose a
     * freshness timestamp than crash the whole cache write.
     */
    private fun parseIsoEpochMillis(iso: String?): Long? {
        val s = iso?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.time.Instant.parse(s).toEpochMilli() }
            .onFailure { Timber.w(it, "Unparseable scanned_at: %s", s) }
            .getOrNull()
    }
}
