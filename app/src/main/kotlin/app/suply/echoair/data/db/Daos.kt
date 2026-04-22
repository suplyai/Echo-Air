package app.suply.echoair.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM shipments WHERE status = 'in_transit'")
    fun activeShipments(): Flow<List<CachedShipment>>
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: CachedDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<CachedDevice>)

    @Query("SELECT * FROM devices WHERE shipmentId = :shipmentId")
    fun forShipment(shipmentId: String): Flow<List<CachedDevice>>

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

    @Query("SELECT COUNT(*) FROM pending_uploads")
    fun pendingCount(): Flow<Int>
}
