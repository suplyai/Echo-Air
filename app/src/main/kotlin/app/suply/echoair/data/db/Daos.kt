package app.suply.echoair.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
@Dao
interface ShipmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shipment: CachedShipment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shipments: List<CachedShipment>)

    @Query("SELECT * FROM shipments WHERE id = :id")
    suspend fun byId(id: String): CachedShipment?

    @Query("SELECT * FROM shipments WHERE awbNumber = :awb")
    suspend fun byAwb(awb: String): CachedShipment?

}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: CachedDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<CachedDevice>)

    @Query("SELECT * FROM devices WHERE shipmentId = :shipmentId")
    suspend fun forShipmentOnce(shipmentId: String): List<CachedDevice>

    @Query("SELECT * FROM devices WHERE deviceId = :id")
    suspend fun byDeviceId(id: String): CachedDevice?

    @Query("SELECT * FROM devices WHERE mac = :mac")
    suspend fun byMac(mac: String): CachedDevice?

    @Query("UPDATE devices SET status = :status, lastSeenAt = :seenAt WHERE deviceId = :id")
    suspend fun updateStatus(id: String, status: String, seenAt: Long?)

    @Query("SELECT DISTINCT deviceId FROM devices WHERE shipmentId IN (SELECT id FROM shipments WHERE status = 'in_transit')")
    suspend fun activeRosterDeviceIds(): List<String>

    @Query("SELECT DISTINCT mac FROM devices WHERE mac IS NOT NULL AND shipmentId IN (SELECT id FROM shipments WHERE status = 'in_transit')")
    suspend fun activeRosterMacs(): List<String>
}

@Dao
interface UnitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(units: List<CachedUnit>)

    @Query("SELECT * FROM units WHERE shipmentId = :shipmentId ORDER BY sequenceIndex ASC, id ASC")
    suspend fun forShipmentOnce(shipmentId: String): List<CachedUnit>

    /** Replace the unit roster for a shipment in one transaction. The
     *  consignee endpoint always returns the full list, so we don't need
     *  partial-update semantics — wipe + insert is correct. */
    @Query("DELETE FROM units WHERE shipmentId = :shipmentId")
    suspend fun deleteForShipment(shipmentId: String)
}

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TemperatureRecord>)

    @Query("SELECT COUNT(*) FROM temperature_records WHERE deviceId = :id")
    suspend fun countFor(id: String): Int

    @Query("SELECT MIN(temperature) AS min, MAX(temperature) AS max FROM temperature_records WHERE deviceId = :id")
    suspend fun tempRange(id: String): TempRange?

    @Query("UPDATE temperature_records SET uploaded = 1 WHERE deviceId = :id")
    suspend fun markUploaded(id: String)
}

data class TempRange(
    val min: Double?,
    val max: Double?
)

@Dao
interface PendingUploadDao {
    @Insert
    suspend fun enqueue(upload: PendingUpload): Long

    @Query("SELECT * FROM pending_uploads ORDER BY createdAt ASC")
    suspend fun all(): List<PendingUpload>

    @Update
    suspend fun update(upload: PendingUpload)

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun delete(id: Long)
}
