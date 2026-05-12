package app.suply.echoair.domain

/**
 * ISO 6346 ocean shipping container number parsing + validation.
 *
 * Format (fixed by the standard): `OOOO U SSSSSS C`
 *   - 4-letter owner code (3 letters + the category identifier U/J/Z)
 *   - 6-digit serial number
 *   - 1-digit check digit
 *
 * Example: `EITU 317174 1`
 *   - owner: EITU
 *   - serial: 317174
 *   - check: 1
 *
 * The check digit is computed by:
 *   1. Mapping each of the first 10 characters to a numeric value: digits map
 *      to themselves; letters map to 10 onwards but SKIP every multiple of 11.
 *      (A=10, B=12, …, K=21, L=23 [skip 22], …, U=32, V=34 [skip 33], …, Z=38.)
 *   2. Multiplying each value by `2^position` (position = 0 for the leftmost
 *      character, 9 for the rightmost of the first ten).
 *   3. Summing all ten products and taking `sum mod 11`. If the result is 10,
 *      the check digit is 0 — a known quirk of the standard. (Most generators
 *      avoid producing such numbers, but a few legit ones exist in the wild;
 *      we must accept them or else block legitimate cargo from being scanned.)
 *
 * Verified against the spec example "EITU3171741":
 *   15·1 + 19·2 + 31·4 + 32·8 + 3·16 + 1·32 + 7·64 + 1·128 + 7·256 + 4·512
 *   = 4929; 4929 mod 11 = 1 → check digit 1 ✓
 */
object Iso6346 {

    /**
     * Coerce [input] into the canonical 11-character form by stripping
     * whitespace and hyphens and uppercasing the result. Returns the cleaned
     * string regardless of length so the UI can run [isWellFormed] against it
     * and surface the format error inline; use [isValid] for the full check.
     */
    fun canonicalise(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }

    /** True iff [canonical] is exactly 4 uppercase letters followed by 7 digits. */
    fun isWellFormed(canonical: String): Boolean = WELL_FORMED.matches(canonical)

    /**
     * Two-stage validation: well-formed AND the trailing digit matches the
     * check digit computed from the first 10 characters. Inputs that fail
     * [isWellFormed] short-circuit to false here so callers can use this for
     * the final accept/reject decision.
     */
    fun isValid(canonical: String): Boolean {
        if (!isWellFormed(canonical)) return false
        return computedCheckDigit(canonical) == canonical[10].digitToInt()
    }

    /**
     * Returns the expected check digit (0–9) for [canonical]'s first 10
     * characters, or null if those 10 characters aren't a valid prefix
     * (4 letters + 6 digits). Useful both for validation and for hinting in
     * the UI while the user is still typing the trailing digit.
     */
    fun computedCheckDigit(canonical: String): Int? {
        if (canonical.length < 10) return null
        if (!PREFIX.matches(canonical.substring(0, 10))) return null
        var sum = 0L
        for (i in 0 until 10) {
            val v = charValue(canonical[i]) ?: return null
            sum += v.toLong() shl i  // 2^i
        }
        // The mod-10 wrap is the standard's quirk: a raw 10 collapses to 0.
        return ((sum % 11L) % 10L).toInt()
    }

    private fun charValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c.digitToInt()
        in 'A'..'Z' -> LETTER_VALUES[c - 'A']
        else -> null
    }

    // Letter values per ISO 6346: A starts at 10, every multiple of 11
    // (i.e. 11, 22, 33) is skipped. Indexed by (c - 'A').
    private val LETTER_VALUES = intArrayOf(
        10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32, 34, 35, 36, 37, 38
    )

    private val WELL_FORMED = Regex("""^[A-Z]{4}\d{7}$""")
    private val PREFIX = Regex("""^[A-Z]{4}\d{6}$""")
}
