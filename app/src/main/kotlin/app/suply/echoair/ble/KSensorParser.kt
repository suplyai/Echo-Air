package app.suply.echoair.ble

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

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
 */
object KSensorParser {

    private val EDDYSTONE_UUID: UUID = UUID.fromString(KBeaconIds.EDDYSTONE_SERVICE)

    fun parse(result: ScanResult): KBeacon? {
        val record = result.scanRecord ?: return null
        val serviceData = record.getServiceData(ParcelUuid(EDDYSTONE_UUID)) ?: return null
        if (serviceData.size < 4) return null
        if (serviceData[0] != KBeaconIds.FRAME_KSENSOR) return null

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

        val mac = KBeaconIds.canonicaliseMac(result.device.address ?: return null)
        val name = record.deviceName ?: runCatching {
            @Suppress("MissingPermission")
            result.device.name
        }.getOrNull() ?: return null

        val serial = KBeaconIds.extractSerialFromName(name) ?: return null

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
}
