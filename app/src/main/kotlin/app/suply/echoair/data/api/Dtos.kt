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
    @SerialName("air_dest_iata") val airDestIata: String? = null,
    @SerialName("transport_mode") val transportMode: String? = null,
    val status: String,
    @SerialName("cargo_profile") val cargoProfile: CargoProfileDto? = null,
    val devices: List<ShipmentDeviceDto> = emptyList()
)

@Serializable
data class ShipmentDeviceDto(
    @SerialName("device_id") val deviceId: String,
    val mac: String? = null,
    val status: String,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null
)

@Serializable
data class ShipmentListResponse(
    val shipments: List<ShipmentDto> = emptyList(),
    val total: Int = 0
)

// ---------- Vision ----------

@Serializable
data class VisionRequest(
    @SerialName("image_base64") val imageBase64: String
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
    val mac: String? = null,
    @SerialName("hardware_type") val hardwareType: String? = null,
    val shipment: ShipmentDto? = null,
    val assigned: Boolean = false
)

// ---------- Echo scan ----------

@Serializable
data class EchoScanRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("temperature_records") val temperatureRecords: List<ReadingDto>
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
