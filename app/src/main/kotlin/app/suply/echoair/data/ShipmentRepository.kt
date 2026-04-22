package app.suply.echoair.data

import android.content.Context
import androidx.work.WorkManager
import app.suply.echoair.data.api.EchoScanRequest
import app.suply.echoair.data.api.EchoScanResponse
import app.suply.echoair.data.api.ReadingDto
import app.suply.echoair.data.api.ShipmentDto
import app.suply.echoair.data.api.SuplyApi
import app.suply.echoair.data.api.VisionRequest
import app.suply.echoair.data.api.VisionResponse
import app.suply.echoair.data.db.CachedDevice
import app.suply.echoair.data.db.CachedShipment
import app.suply.echoair.data.db.DeviceDao
import app.suply.echoair.data.db.PendingUpload
import app.suply.echoair.data.db.PendingUploadDao
import app.suply.echoair.data.db.RecordDao
import app.suply.echoair.data.db.ShipmentDao
import app.suply.echoair.data.db.TemperatureRecord
import app.suply.echoair.work.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    private val recordDao: RecordDao,
    private val uploadDao: PendingUploadDao,
    private val json: Json
) {

    fun activeShipments(): Flow<List<CachedShipment>> = shipmentDao.activeShipments()

    fun devicesFor(shipmentId: String): Flow<List<CachedDevice>> =
        deviceDao.forShipment(shipmentId)

    suspend fun identifyFromImage(imageDataUrl: String): VisionResponse {
        val resp = api.identifyShipment(VisionRequest(imageBase64 = imageDataUrl))
        resp.shipment?.let { cache(it) }
        return resp
    }

    suspend fun lookupDevice(identifier: String): ShipmentDto? {
        val resp = api.lookupDevice(identifier, includeShipment = true)
        resp.shipment?.let { cache(it) }
        return resp.shipment
    }

    suspend fun searchByAwb(query: String): List<ShipmentDto> {
        val resp = api.listShipments(status = "in_transit", search = query)
        resp.shipments.forEach { cache(it) }
        return resp.shipments
    }

    suspend fun refreshActiveRoster(): List<ShipmentDto> {
        val resp = api.listShipments(status = "in_transit")
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
     */
    suspend fun submitRecords(
        deviceId: String,
        records: List<ReadingDto>
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

        val request = EchoScanRequest(deviceId = deviceId, temperatureRecords = records)
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

    suspend fun activeRosterDeviceIds(): List<String> = deviceDao.activeRosterDeviceIds()
    suspend fun activeRosterMacs(): List<String> = deviceDao.activeRosterMacs()

    suspend fun activeRosterDeviceIdsFor(shipmentId: String): List<CachedDevice> =
        deviceDao.forShipmentOnce(shipmentId)

    suspend fun deviceByMac(mac: String): CachedDevice? = deviceDao.byMac(mac)
    suspend fun deviceByDeviceId(id: String): CachedDevice? = deviceDao.byDeviceId(id)

    private suspend fun cache(s: ShipmentDto) {
        shipmentDao.upsert(
            CachedShipment(
                id = s.id,
                awbNumber = s.airwayBillNumber,
                originIata = s.airOriginIata,
                destIata = s.airDestIata,
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
                    lastSeenAt = it.lastSeenAt
                )
            }
        )
    }
}
