package app.suply.echoair.domain

import android.content.Context
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * O(1) lookup of IATA carrier name from a zero-padded 3-digit airline
 * prefix (e.g. "145" → "Lan Cargo"). Backed by the
 * assets/iata_prefixes.json asset bundled at build time.
 *
 * Why bundled rather than fetched:
 *   - Resolves instantly as the user types the third digit; no network.
 *   - Works offline — warehouses have patchy signal.
 *   - ~8KB asset size, negligible APK bloat.
 *   - Prefix drift is roughly annual; app-release refresh is fine.
 *   - Non-authoritative / informational — doesn't gate submission.
 *
 * Load once at app init via [warmup]; thereafter [carrierName] is a pure
 * Map.get(). Lookup failure returns null (callers surface "Unknown airline
 * prefix" without blocking the user — unknown prefix ≠ error).
 */
object IataCarriers {

    @Volatile private var prefixes: Map<String, String> = emptyMap()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Loads the asset into memory synchronously. Call from Application.onCreate
     * so the first user keystroke on the AWB prefix field hits a warm cache.
     * Safe to call multiple times — first call wins, later calls no-op.
     */
    fun warmup(context: Context) {
        if (prefixes.isNotEmpty()) return
        synchronized(this) {
            if (prefixes.isNotEmpty()) return
            prefixes = runCatching {
                context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                    .let { json.decodeFromString<Map<String, String>>(it) }
            }.onFailure { Timber.w(it, "IATA prefixes asset failed to load from %s", ASSET_PATH) }
                .getOrElse { emptyMap() }
            Timber.d("IataCarriers loaded %d entries", prefixes.size)
        }
    }

    /** Returns the carrier name for [prefix], or null if not catalogued. */
    fun carrierName(prefix: String): String? = prefixes[prefix]

    private const val ASSET_PATH = "iata_prefixes.json"
}
