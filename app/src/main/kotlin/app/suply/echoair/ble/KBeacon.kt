package app.suply.echoair.ble

/**
 * Domain model for the KKM S23H (Echo Air variant).
 *
 * The Suply platform's `device_id` is the KKM serial (e.g. "633640"); the
 * BLE MAC (e.g. "BC57291CD6A6") is the radio-layer identifier. The backend
 * keeps the mapping, but we also keep both locally for fast advertisement
 * filtering.
 */
data class KBeacon(
    val serial: String,           // "633640"
    val mac: String,              // canonical 12-char hex, uppercase, no separators
    val name: String,             // raw advertised name, e.g. "KBPro_633640"
    val rssi: Int,
    val temperatureC: Double?,
    val humidity: Double?,
    val batteryMv: Int?,
    val batteryPercent: Int?,     // derived from mV via BatteryCurve (CR2032)
    val alarm: Boolean,
    val recordCount: Int?,
    val seenAt: Long
) {
    val macFormatted: String
        get() = mac.chunked(2).joinToString(":")
}

object KBeaconIds {
    /** Eddystone service UUID used for filter. */
    const val EDDYSTONE_SERVICE = "0000feaa-0000-1000-8000-00805f9b34fb"

    /** KSensor extension frame type (first byte of service data). */
    const val FRAME_KSENSOR: Byte = 0x21

    /** Default password for stock-provisioned devices. */
    const val DEFAULT_PASSWORD = "0000000000000000"

    /**
     * Device name prefix for advertisements. Case-insensitive in practice —
     * KKM firmware has shipped both "KBPRO_<serial>" and "KBPro_<serial>"
     * in the Complete Local Name field, so all comparisons against this
     * prefix must use ignoreCase = true.
     */
    const val NAME_PREFIX = "KBPRO_"

    fun canonicaliseMac(mac: String): String =
        mac.uppercase().replace(":", "").replace("-", "")

    fun extractSerialFromName(name: String?): String? {
        val n = name ?: return null
        if (!n.startsWith(NAME_PREFIX, ignoreCase = true)) return null
        val serial = n.substring(NAME_PREFIX.length)
        return serial.ifEmpty { null }
    }
}
