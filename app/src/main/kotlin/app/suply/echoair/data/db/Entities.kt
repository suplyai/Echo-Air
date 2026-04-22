package app.suply.echoair.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

@Entity(tableName = "shipments")
data class CachedShipment(
    @PrimaryKey val id: String,
    val awbNumber: String,
    val originIata: String?,
    val destIata: String?,
    val commodityName: String?,
    val commodityMinTemp: Double?,
    val commodityMaxTemp: Double?,
    val status: String,
    val updatedAt: Long
)

@Entity(
    tableName = "devices",
    indices = [Index("shipmentId"), Index("mac", unique = true), Index(value = ["deviceId"], unique = true)]
)
data class CachedDevice(
    @PrimaryKey val deviceId: String,  // KKM serial, e.g. "633640"
    val mac: String?,                  // e.g. "BC57291CD6A6"
    val shipmentId: String?,
    val status: String,                // assigned | scanned | missing
    val lastSeenAt: Long?
)

@Entity(
    tableName = "temperature_records",
    indices = [Index("deviceId"), Index("timestamp")]
)
data class TemperatureRecord(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val deviceId: String,
    val timestamp: Long,       // epoch seconds
    val temperature: Double,
    val humidity: Double?,
    val uploaded: Boolean = false
)

/**
 * Rows in this table represent an /api/echo-scan POST that is queued for
 * retry. Each row holds a blob of records for a single device; WorkManager
 * picks them up and clears the row on success.
 */
@Entity(tableName = "pending_uploads", indices = [Index("deviceId")])
data class PendingUpload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val payloadJson: String,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val createdAt: Long
)

class Converters {
    @TypeConverter fun stringListToJson(list: List<String>?): String =
        Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer()), list ?: emptyList())

    @TypeConverter fun jsonToStringList(json: String?): List<String> =
        if (json.isNullOrBlank()) emptyList()
        else Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer()), json)
}
