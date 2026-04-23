package app.suply.echoair.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses KSensor (0x21) service data frames from kkm devices. Layout follows
 * the KKM S23H spec:
 *
 *   Byte 0       : frame type (0x21)
 *   Byte 1       : salt / version
 *   Byte 2..3    : sensor mask (u16 big-endian) — bit flags for which channels follow
 *   Byte 4..     : channel payloads, ordered by bit position
 *
 * Channel bits of interest for the app:
 *   bit 0 : voltage (u16 mV)
 *   bit 1 : temperature (s16, 0.01 °C)
 *   bit 2 : humidity (u16, 0.01 %RH)
 *   bit 3 : alarm (u8, bitfield)
 *   bit 4 : record count (u16)
 *
 * The kbeaconlib2 library ultimately owns the canonical parsing; this parser
 * is the fast path used during scanning without establishing a GATT link. If
 * the advertisement layout changes, prefer delegating to KBeaconsMgr's parsed
 * KBAdvPacketSensor rather than expanding this parser.
 *
 * Debug logging is rate-limited per-MAC: each unique advertisement source
 * logs one "accepted" or "rejected because …" line on its first appearance,
 * so we can tell at a glance whether the scanner is seeing the target device
 * and, if so, why the parser accepted or dropped its adv. Subsequent frames
 * from the same MAC don't log (BLE adv traffic is high — a bare Timber.d on
 * every frame would swamp logcat).
 */
object KSensorParser {

    private val EDDYSTONE_UUID: UUID = UUID.fromString(KBeaconIds.EDDYSTONE_SERVICE)

    /** Per-MAC rate limit: one log line per (MAC, outcome) pair, forever. Cleared by process exit. */
    private val loggedOutcomes = ConcurrentHashMap.newKeySet<String>()

    @SuppressLint("MissingPermission")
    fun parse(result: ScanResult): KBeacon? {
        val address = result.device.address ?: return null

        val record = result.scanRecord
        if (record == null) {
            logOnce(address, "no scan record") ; return null
        }
        val serviceData = record.getServiceData(ParcelUuid(EDDYSTONE_UUID))
        if (serviceData == null) {
            logOnce(address, "no Eddystone (0xFEAA) service data") ; return null
        }
        if (serviceData.size < 4) {
            logOnce(address, "service data too short (${serviceData.size}B)") ; return null
        }
        if (serviceData[0] != KBeaconIds.FRAME_KSENSOR) {
            logOnce(address, "frame type 0x%02X != KSensor 0x21".format(serviceData[0].toInt() and 0xFF)) ; return null
        }

        val buf = ByteBuffer.wrap(serviceData).order(ByteOrder.BIG_ENDIAN)
        buf.position(2)
        val mask = buf.short.toInt() and 0xFFFF

        var voltage: Int? = null
        var temperature: Double? = null
        var humidity: Double? = null
        var alarm = false
        var recordCount: Int? = null

        fun remaining(): Int = buf.remaining()

        if (mask and 0x01 != 0 && remaining() >= 2) voltage = buf.short.toInt() and 0xFFFF
        if (mask and 0x02 != 0 && remaining() >= 2) temperature = buf.short.toInt() / 100.0
        if (mask and 0x04 != 0 && remaining() >= 2) humidity = (buf.short.toInt() and 0xFFFF) / 100.0
        if (mask and 0x08 != 0 && remaining() >= 1) alarm = buf.get().toInt() and 0xFF != 0
        if (mask and 0x10 != 0 && remaining() >= 2) recordCount = buf.short.toInt() and 0xFFFF

        val mac = KBeaconIds.canonicaliseMac(address)
        val name = record.deviceName ?: runCatching { result.device.name }.getOrNull()
        if (name == null) {
            logOnce(address, "no Complete/Shortened Local Name in adv") ; return null
        }

        val serial = KBeaconIds.extractSerialFromName(name)
        if (serial == null) {
            logOnce(address, "name '$name' doesn't start with expected prefix '${KBeaconIds.NAME_PREFIX}'") ; return null
        }

        logOnce(address, "accepted (serial=$serial rssi=${result.rssi} mask=0x%04X)".format(mask))
        return KBeacon(
            serial = serial,
            mac = mac,
            name = name,
            rssi = result.rssi,
            temperatureC = temperature,
            humidity = humidity,
            batteryMv = voltage,
            alarm = alarm,
            recordCount = recordCount,
            seenAt = System.currentTimeMillis()
        )
    }

    private fun logOnce(mac: String, outcome: String) {
        val key = "$mac|$outcome"
        if (loggedOutcomes.add(key)) Timber.d("KSensor %s: %s", mac, outcome)
    }
}
