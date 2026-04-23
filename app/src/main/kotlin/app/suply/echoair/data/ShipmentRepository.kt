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
        deviceClockOffsetSeconds: Long? = null
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
            deviceClockOffsetSeconds = deviceClockOffsetSeconds
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
                    lastSeenAt = it.scannedAt      // backend renamed from last_seen_at
                )
            }
        )
    }
}
