package app.suply.echoair.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses KSensor (0x21) service data frames from KKM devices. Layout is
 * (verified against kbeaconlib2's own parser — KBAdvPacketSensor.parseAdvPacket
 * plus .parseSensorData — and real-hardware advertisements):
 *
 *   Byte 0       : frame type (0x21 for KKM V1 KSensor)
 *   Byte 1..2    : sensor mask (u16 big-endian) — bit flags, see below
 *   Byte 3..     : channel payloads, in bit order
 *
 * The original brief described a "salt" byte at position 1 with mask at 2..3.
 * That doesn't match what KKM ships; the real layout has mask at 1..2 with
 * no salt. Getting this wrong shifts every subsequent field by one byte and
 * makes every mask-bit check look at the wrong nibble — which was the origin
 * of the 4% "battery" reading (the decoded mask happened to include the
 * HUME bit in the wrong position, and its payload decoded to ≈4.1%).
 *
 * Sensor mask bits (matches KKM constants):
 *   0x001 VOLTAGE       2 bytes u16 big-endian (mV)
 *   0x002 TEMP          2 bytes KKM signedBytes2Float: int16 / 256.0 (°C)
 *   0x004 HUME          2 bytes KKM signedBytes2Float: int16 / 256.0 (%RH)
 *                        (if high byte ≤ -3 the two bytes are a CPU-temp
 *                         escape, not humidity — we skip the payload and
 *                         leave humidity null)
 *   0x008 ACC_AIX       6 bytes (3 × int16, x/y/z) — we skip
 *   0x010 ALARM         1 byte bitfield
 *   0x020 PIR           1 byte — skip
 *   0x040 LUX           2 bytes — skip
 *   0x080 VOC           5 bytes — skip
 *   0x200 CO2           5 bytes — skip (we don't care for Echo Air)
 *   0x400 RECORD_NUM    2 bytes u16 (unread record count)
 *
 * Debug logging is rate-limited per-MAC: each unique advertisement source
 * logs one "accepted" or "rejected because …" line on its first appearance.
 */
object KSensorParser {

    private val EDDYSTONE_UUID: UUID = UUID.fromString(KBeaconIds.EDDYSTONE_SERVICE)

    private const val MASK_VOLTAGE  = 0x001
    private const val MASK_TEMP     = 0x002
    private const val MASK_HUME     = 0x004
    private const val MASK_ACC      = 0x008
    private const val MASK_ALARM    = 0x010
    private const val MASK_PIR      = 0x020
    private const val MASK_LUX      = 0x040
    private const val MASK_VOC      = 0x080
    private const val MASK_CO2      = 0x200
    private const val MASK_RECORD   = 0x400

    /** Per-MAC rate limit: one log line per (MAC, outcome) pair per process lifetime. */
    private val loggedOutcomes = ConcurrentHashMap.newKeySet<String>()

    @SuppressLint("MissingPermission")
    fun parse(result: ScanResult): KBeacon? {
        val address = result.device.address ?: return null

        val record = result.scanRecord
        if (record == null) { logOnce(address, "no scan record"); return null }
        val serviceData = record.getServiceData(ParcelUuid(EDDYSTONE_UUID))
        if (serviceData == null) { logOnce(address, "no Eddystone (0xFEAA) service data"); return null }
        if (serviceData.size < 3) { logOnce(address, "service data too short (${serviceData.size}B)"); return null }
        if (serviceData[0] != KBeaconIds.FRAME_KSENSOR) {
            logOnce(address, "frame type 0x%02X != KSensor 0x21".format(serviceData[0].toInt() and 0xFF))
            return null
        }

        // Mask is bytes 1..2 big-endian, payload starts at byte 3. See class kdoc.
        val mask = ((serviceData[1].toInt() and 0xFF) shl 8) or (serviceData[2].toInt() and 0xFF)
        var i = 3

        fun bytesLeft() = serviceData.size - i
        fun u8(): Int = serviceData[i++].toInt() and 0xFF
        fun u16be(): Int { val hi = u8(); val lo = u8(); return (hi shl 8) or lo }
        fun s16as256(): Float {
            val hi = serviceData[i++].toInt()
            val lo = serviceData[i++].toInt() and 0xFF
            var combined = ((hi and 0xFF) shl 8) or lo
            if (combined >= 0x8000) combined -= 0x10000
            return combined / 256f
        }
        fun skip(n: Int) { i += n }

        var voltage: Int? = null
        var temperature: Double? = null
        var humidity: Double? = null
        var alarm = false
        var recordCount: Int? = null

        if (mask and MASK_VOLTAGE != 0 && bytesLeft() >= 2) voltage = u16be()
        if (mask and MASK_TEMP != 0 && bytesLeft() >= 2) temperature = s16as256().toDouble()
        if (mask and MASK_HUME != 0 && bytesLeft() >= 2) {
            // KKM quirk: if the high byte is < -2 (signed), the two bytes are a
            // CPU-temperature escape, not humidity. We don't use CPU temp — skip
            // it and leave humidity null.
            val peekHigh = serviceData[i].toInt()
            if (peekHigh >= -2) humidity = s16as256().toDouble() else skip(2)
        }
        if (mask and MASK_ACC != 0   && bytesLeft() >= 6) skip(6)
        if (mask and MASK_ALARM != 0 && bytesLeft() >= 1) alarm = u8() != 0
        if (mask and MASK_PIR != 0   && bytesLeft() >= 1) skip(1)
        if (mask and MASK_LUX != 0   && bytesLeft() >= 2) skip(2)
        if (mask and MASK_VOC != 0   && bytesLeft() >= 5) skip(5)
        if (mask and MASK_CO2 != 0   && bytesLeft() >= 5) skip(5)
        if (mask and MASK_RECORD != 0 && bytesLeft() >= 2) recordCount = u16be()

        val mac = KBeaconIds.canonicaliseMac(address)
        val name = record.deviceName ?: runCatching { result.device.name }.getOrNull()
        if (name == null) { logOnce(address, "no Complete/Shortened Local Name in adv"); return null }

        val serial = KBeaconIds.extractSerialFromName(name)
        if (serial == null) {
            logOnce(address, "name '$name' doesn't start with expected prefix '${KBeaconIds.NAME_PREFIX}'")
            return null
        }

        logOnce(address, "accepted (serial=$serial rssi=${result.rssi} mask=0x%04X mV=$voltage)".format(mask))
        return KBeacon(
            serial = serial,
            mac = mac,
            name = name,
            rssi = result.rssi,
            temperatureC = temperature,
            humidity = humidity,
            batteryMv = voltage,
            batteryPercent = voltage?.let(BatteryCurve::cr2032PercentFromMv),
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

/**
 * CR2032 coin-cell voltage-to-percent mapping. Real discharge curves sit near
 * 3.0 V for most of the cell's life and drop sharply at end-of-life, so a
 * linear 2.0 V → 3.0 V remap makes a fresh cell look half-dead. This
 * piecewise-linear curve matches the shape of the manufacturer data well
 * enough for status-icon purposes (we only care about bucket accuracy —
 * "full / most / some / low / dead" — not sub-percent precision).
 *
 *   ≥ 3.00 V → 100%
 *     2.90 V →  80%
 *     2.80 V →  50%
 *     2.70 V →  20%
 *     2.60 V →  10%
 *   ≤ 2.40 V →   0%
 */
internal object BatteryCurve {
    private data class Point(val mv: Int, val pct: Int)
    private val curve = listOf(
        Point(mv = 3000, pct = 100),
        Point(mv = 2900, pct =  80),
        Point(mv = 2800, pct =  50),
        Point(mv = 2700, pct =  20),
        Point(mv = 2600, pct =  10),
        Point(mv = 2400, pct =   0),
    )

    fun cr2032PercentFromMv(mv: Int): Int {
        if (mv >= curve.first().mv) return 100
        if (mv <= curve.last().mv) return 0
        for (k in 0 until curve.size - 1) {
            val hi = curve[k]; val lo = curve[k + 1]
            if (mv <= hi.mv && mv >= lo.mv) {
                val span = (hi.mv - lo.mv).toDouble()
                val frac = (mv - lo.mv) / span
                return (lo.pct + frac * (hi.pct - lo.pct)).toInt().coerceIn(0, 100)
            }
        }
        return 0
    }
}
