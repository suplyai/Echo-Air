package app.suply.echoair.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- Auth ----------

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserDto? = null
)

@Serializable
data class UserDto(
    val id: String? = null,
    val email: String? = null,
    @SerialName("org_id") val orgId: String? = null,
    val role: String? = null
)

// ---------- Cargo profile ----------

@Serializable
data class CargoProfileDto(
    val name: String? = null,
    /**
     * High-level commodity category used to pick an icon + accent colour in
     * the confirmation sheet (e.g. "flowers", "seafood", "pharma", "fruit",
     * "meat", "dairy"). Optional: if absent, the sheet falls back to a
     * generic cargo icon. Backend already stores this in
     * `master_commodity_profiles.profile_data` but may not expose it on
     * the shipment lookup response yet — ping the Suply engineer if
     * production responses are missing it.
     */
    val category: String? = null,
    @SerialName("min_temp") val minTemp: Double? = null,
    @SerialName("max_temp") val maxTemp: Double? = null,
    @SerialName("min_humidity") val minHumidity: Double? = null,
    @SerialName("max_humidity") val maxHumidity: Double? = null
)

// ---------- Shipment ----------

@Serializable
data class ShipmentDto(
    val id: String,
    @SerialName("airway_bill_number") val airwayBillNumber: String,
    @SerialName("air_origin_iata") val airOriginIata: String? = null,
    /** City name for the origin airport, e.g. "Lima" for LIM. Optional;
     *  the confirmation sheet shows code alone when the city is missing. */
    @SerialName("air_origin_city") val airOriginCity: String? = null,
    @SerialName("air_dest_iata") val airDestIata: String? = null,
    /** City name for the destination airport, e.g. "Amsterdam" for AMS. */
    @SerialName("air_dest_city") val airDestCity: String? = null,
    @SerialName("transport_mode") val transportMode: String? = null,
    val status: String,
    /**
     * Commodity identity at shipment root (backend's chosen shape as of
     * v0.3.4 — not nested under cargo_profile). Drives the hero text and
     * category accent on the confirmation sheet. CargoProfileDto still
     * carries temperature/humidity bounds for the collection screen.
     */
    @SerialName("commodity_name") val commodityName: String? = null,
    @SerialName("commodity_category") val commodityCategory: String? = null,
    @SerialName("cargo_profile") val cargoProfile: CargoProfileDto? = null,
    val devices: List<ShipmentDeviceDto> = emptyList()
)

@Serializable
data class ShipmentDeviceDto(
    @SerialName("device_id") val deviceId: String,
    /**
     * MAC address in colon-formatted form ("BC:57:29:1C:D6:A6"). Backend
     * field is `mac_address`; older deployments may have sent `mac` — keep
     * the @SerialName pinned to the canonical name to avoid accidental
     * field-rename breakage.
     */
    @SerialName("mac_address") val mac: String? = null,
    /** KKM serial. For Echo Air devices this is the same value as device_id. */
    val serial: String? = null,
    val model: String? = null,
    val status: String,
    /** True once the destination consignee has scanned this device. */
    @SerialName("echo_scanned") val echoScanned: Boolean? = null,
    /** 1-based ordering across scans on the shipment. */
    @SerialName("scan_sequence") val scanSequence: Int? = null,
    /**
     * ISO-8601 instant of the last successful scan (e.g. "2026-04-23T15:41:29.900Z").
     * Kept as a string on the wire rather than epoch millis because the backend
     * emits ISO-8601; parse with [java.time.Instant.parse] on the app side when
     * an epoch representation is needed (see ShipmentRepository.cache).
     */
    @SerialName("scanned_at") val scannedAt: String? = null,
    /** Inferred position in the cargo (pallet / stack / etc.) — free-form. */
    @SerialName("inferred_position") val inferredPosition: String? = null
)

@Serializable
data class ShipmentListResponse(
    val shipments: List<ShipmentDto> = emptyList(),
    val total: Int = 0
)

// ---------- Vision ----------

/**
 * The vision endpoint accepts either a base64-encoded document image OR a
 * manually-typed AWB number (for the "Enter manually" fallback UX). One of
 * the two must be non-null on any given request; nulls are omitted on the
 * wire via the JSON config's explicitNulls = false.
 */
@Serializable
data class VisionRequest(
    @SerialName("image_base64") val imageBase64: String? = null,
    @SerialName("awb_number") val awbNumber: String? = null
)

@Serializable
data class VisionResponse(
    @SerialName("awb_number") val awbNumber: String? = null,
    val confidence: String? = null,          // "high" | "medium" | "low"
    val reasoning: String? = null,
    val shipment: ShipmentDto? = null
)

// ---------- Device lookup ----------

@Serializable
data class DeviceLookupResponse(
    @SerialName("device_id") val deviceId: String,
    /** Backend renamed this from `mac` to `mac_address` alongside the devices[] rename. */
    @SerialName("mac_address") val mac: String? = null,
    val serial: String? = null,
    val model: String? = null,
    @SerialName("hardware_type") val hardwareType: String? = null,
    val shipment: ShipmentDto? = null,
    val assigned: Boolean = false
)

// ---------- Echo scan ----------

@Serializable
data class EchoScanRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("temperature_records") val temperatureRecords: List<ReadingDto>,
    /**
     * Phone UTC minus device UTC at the start of readout, seconds. Sent
     * unmodified to the backend — timestamps on [temperatureRecords] are
     * the raw values from the device clock, and the backend's fusion
     * layer applies the correction against waybill + Hubble scans.
     */
    @SerialName("device_clock_offset_seconds") val deviceClockOffsetSeconds: Long? = null
)

@Serializable
data class ReadingDto(
    val temperature: Double,
    val humidity: Double? = null,
    val timestamp: Long
)

@Serializable
data class EchoScanResponse(
    @SerialName("shipment_id") val shipmentId: String? = null,
    @SerialName("devices_scanned") val devicesScanned: Int = 0,
    @SerialName("devices_total") val devicesTotal: Int = 0,
    @SerialName("all_scanned") val allScanned: Boolean = false,
    @SerialName("siblings_pending") val siblingsPending: List<String> = emptyList(),
    @SerialName("this_device_alert") val thisDeviceAlert: Boolean = false,
    @SerialName("temperature_alert") val temperatureAlert: Boolean = false
)

// ---------- Error ----------

@Serializable
data class ApiError(
    val error: String? = null,
    val message: String? = null,
    val code: String? = null
)
