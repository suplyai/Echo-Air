package app.suply.echoair.domain

/**
 * IATA Air Waybill (AWB) number parsing + validation.
 *
 * Format (fixed everywhere in the industry): `XXX-XXXXXXXX`
 *   - 3-digit airline prefix (IATA carrier numeric code, e.g. 057 for Air France)
 *   - 8-digit serial, in which the last digit is a mod-7 check digit on the
 *     preceding 7 digits.
 *
 * Reference: IATA Cargo-XML / Resolution 600a. The check digit is derived as
 * `firstSevenDigits % 7`, so by construction it can only be 0–6; any AWB that
 * parses cleanly but has an 8th digit of 7, 8, or 9 is invalid.
 *
 * Example: 145-12863723
 *   - airline prefix: 145
 *   - serial: 12863723
 *   - first-7 of serial: 1286372
 *   - 1286372 % 7 = 3 → matches check digit, valid ✓
 */
object Awb {

    /**
     * Try to coerce [input] into canonical `XXX-XXXXXXXX` form by extracting
     * digits. Accepts: "145-12863723", "14512863723", "145 12863723",
     * "AWB: 145-12863723", etc. Returns null if the input doesn't contain
     * exactly 11 digits.
     */
    fun canonicalise(input: String): String? {
        val digits = input.filter(Char::isDigit)
        if (digits.length != 11) return null
        return "${digits.substring(0, 3)}-${digits.substring(3)}"
    }

    /** Returns true iff [awb] is in canonical form AND the check digit matches. */
    fun isValid(awb: String): Boolean {
        val (prefix, serial) = split(awb) ?: return false
        if (prefix.length != 3 || serial.length != 8) return false
        return checkDigitOf(serial) == serial.last().digitToInt()
    }

    /**
     * Returns the expected check digit for [serial]'s first 7 digits, or null
     * if [serial] is not 7 or 8 digits of pure digit characters. Useful both
     * for validation and for computing what the check digit *should* be while
     * a user is typing.
     */
    fun expectedCheckDigit(serial: String): Int? {
        if (serial.length !in 7..8) return null
        if (!serial.all(Char::isDigit)) return null
        return checkDigitOf(serial)
    }

    private fun checkDigitOf(serial: String): Int =
        (serial.substring(0, 7).toLong() % 7).toInt()

    /** Splits `XXX-XXXXXXXX` into (prefix, serial). Returns null on malformed input. */
    fun split(awb: String): Pair<String, String>? {
        val m = AWB_PATTERN.matchEntire(awb) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private val AWB_PATTERN = Regex("""^(\d{3})-?(\d{8})$""")
}
