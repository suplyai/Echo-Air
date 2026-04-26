package app.suply.echoair.ui.locale

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import timber.log.Timber
import java.util.Locale

/**
 * The four languages the app ships translations for. Supported language
 * tags mirror the values-* resource qualifiers. Order here controls the
 * order in the language picker.
 *
 * [displayCode] is what the home-screen language pill renders next to
 * the globe icon — Latin abbreviation for the Latin-script locales,
 * the language's own native character for the CJK pair so it reads
 * natural to a speaker glancing at the chip.
 */
enum class AppLocale(val tag: String, val nativeNameKey: String, val displayCode: String) {
    ENGLISH("en", "language_name_en", "EN"),
    SPANISH("es", "language_name_es", "ES"),
    CHINESE("zh", "language_name_zh", "中"),
    JAPANESE("ja", "language_name_ja", "日");

    companion object {
        val DEFAULT = ENGLISH

        /** Tag → AppLocale, normalising region variants (es-MX → SPANISH etc.). */
        fun fromTag(tag: String?): AppLocale? {
            val base = tag?.substringBefore('-')?.lowercase()?.ifBlank { null } ?: return null
            return entries.firstOrNull { it.tag == base }
        }

        /** What the device's default locale maps to, or null if unsupported. */
        fun fromSystemDefault(): AppLocale? =
            fromTag(Locale.getDefault().toLanguageTag())
    }
}

/**
 * Per-app locale persistence + apply.
 *
 * AppCompatDelegate.setApplicationLocales is the primary mechanism, but
 * it has two failure modes we have to defend against:
 *   1. On Compose-only apps (no AppCompatActivity), the AppCompat library
 *      needs the AppLocalesMetadataHolderService declared in the manifest
 *      with autoStoreLocales=true. We do declare it, but if a future
 *      manifest merge regresses that, the call silently no-ops.
 *   2. On API 33+ the OS handles persistence directly via the platform
 *      LocaleManager. Some OEM skins (Honor / MagicOS confirmed) have
 *      been observed eating the platform call too.
 *
 * Belt-and-suspenders: we also mirror the chosen tag into our own
 * SharedPreferences in [apply], and re-apply it from
 * [restoreFromPreferences] at app start (called from EchoAirApp.onCreate)
 * regardless of whether AppCompat already restored. Idempotent — if
 * AppCompat did persist correctly, the second apply is a no-op.
 */
object LocaleManager {

    private const val PREFS = "echoair_locale"
    private const val KEY_FIRST_LAUNCH_DONE = "first_launch_confirmed"
    private const val KEY_CHOSEN_TAG = "chosen_language_tag"

    /** The language currently applied to the app, or null if the OS is using its default. */
    fun current(): AppLocale? {
        val list = AppCompatDelegate.getApplicationLocales()
        if (list.isEmpty) return null
        return AppLocale.fromTag(list.get(0)?.toLanguageTag())
    }

    /**
     * Apply [locale] across the app immediately. The write is the
     * authoritative part — the UI still needs Activity.recreate() to
     * pick up the new Configuration, which the caller is responsible
     * for firing (see HomeScreen + FirstLaunchLanguageGate). We also
     * notify AppCompatDelegate for the API 33+ Settings surfacing and
     * to keep that library's internal cache coherent, but we no longer
     * trust it as the sole persistence path.
     *
     * Safe to call from any thread.
     */
    fun apply(context: Context, locale: AppLocale) {
        Timber.i("LocaleManager.apply(%s) — writing prefs + delegate", locale.tag)
        // Our own store is the source of truth that MainActivity.
        // attachBaseContext reads on each recreation.
        prefs(context).edit().putString(KEY_CHOSEN_TAG, locale.tag).apply()
        // Best-effort: inform AppCompat + the platform LocaleManager.
        // Ignored silently by some OEM skins; recreate() by the caller
        // guarantees the UI picks up the change regardless.
        runCatching {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.tag))
        }
    }

    /**
     * The locale the user last chose, or null if they never chose one.
     * Read by Activity.attachBaseContext on every (re)creation so the
     * Resources resolve against the right language with no dependency
     * on AppCompat or platform LocaleManager doing the right thing.
     */
    fun storedLocale(context: Context): AppLocale? {
        val tag = prefs(context).getString(KEY_CHOSEN_TAG, null) ?: return null
        return AppLocale.fromTag(tag)
    }

    /**
     * Re-apply the stored choice's side-effects (AppCompatDelegate,
     * platform LocaleManager) at app start. The authoritative Context
     * wrapping happens in MainActivity.attachBaseContext; this call is
     * purely for keeping AppCompat's internal state coherent.
     */
    fun restoreFromPreferences(context: Context) {
        val stored = storedLocale(context) ?: return
        val applied = current()
        if (applied == stored) {
            Timber.d("LocaleManager.restore: AppCompatDelegate already %s, skipping", stored.tag)
            return
        }
        Timber.i("LocaleManager.restore: delegating %s (was %s)", stored.tag, applied?.tag)
        runCatching {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored.tag))
        }
    }

    fun hasConfirmedFirstLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    fun markFirstLaunchConfirmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
