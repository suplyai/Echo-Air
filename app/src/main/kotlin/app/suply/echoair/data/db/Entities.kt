package app.suply.echoair.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "shipments")
data class CachedShipment(
    @PrimaryKey val id: String,
    /** Air-freight reference. Nullable as of v0.7.0 because ocean reefer
     *  shipments use [containerNumber] instead. The collection title bar
     *  picks the populated identifier based on [transportMode]. */
    val awbNumber: String?,
    val originIata: String?,
    val destIata: String?,
    // City names for the origin/destination airports. Optional; when
    // present, the Collection header renders "Lima (LIM) → Amsterdam
    // (AMS)" instead of a bare IATA pair. Backend emits these via
    // air_origin_city / air_dest_city on ShipmentDto.
    val originCity: String?,
    val destCity: String?,
    /** Ocean-freight reference (ISO 6346 container number). Mutually
     *  exclusive with [awbNumber] in practice — see [transportMode]. */
    val containerNumber: String?,
    /** "air_freight" or "ocean_reefer" as of v0.7.0. Null on cached rows
     *  written before the schema bump (those upgrade via destructive
     *  fallback, so the field is effectively always populated). */
    val transportMode: String?,
    val commodityName: String?,
    val commodityMinTemp: Double?,
    val commodityMaxTemp: Double?,
    val status: String,
    val updatedAt: Long
)

@Entity(
    tableName = "devices",
    indices = [
        Index("shipmentId"),
        Index("mac", unique = true),
        Index(value = ["deviceId"], unique = true),
        Index("unitId")
    ]
)
data class CachedDevice(
    @PrimaryKey val deviceId: String,  // KKM serial, e.g. "633640"
    val mac: String?,                  // e.g. "BC57291CD6A6"
    val shipmentId: String?,
    val status: String,                // assigned | scanned | missing
    val lastSeenAt: Long?,
    /** Multi-unit attribution. Null for legacy single-unit data, for the
     *  synthetic "Unattributed" edge case, or for shipments cached before
     *  v0.4.9. The Collection screen falls back to flat rendering when
     *  this is null across the roster. */
    val unitId: String? = null
)

/**
 * One pallet / ULD / lot inside a Multiple Package Shipment (MPS). Cached
 * mirror of [app.suply.echoair.data.api.UnitDto]. The dashboard side stays
 * authoritative; this row exists so the Collection screen can render unit
 * headers and per-unit progress without re-fetching the shipment on every
 * recomposition.
 */
@Entity(
    tableName = "units",
    indices = [Index("shipmentId")]
)
data class CachedUnit(
    @PrimaryKey val id: String,
    val shipmentId: String,
    /** Customer-supplied label. Rendered verbatim — see the v0.4.9 brief
     *  vocabulary note: customer's label always wins over Suply's. */
    val label: String?,
    val position: String?,
    val sequenceIndex: Int?,
    val commodityOverride: String?
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

