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
 */
enum class AppLocale(val tag: String, val nativeNameKey: String) {
    ENGLISH("en", "language_name_en"),
    SPANISH("es", "language_name_es"),
    CHINESE("zh", "language_name_zh"),
    JAPANESE("ja", "language_name_ja");

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
     * Apply [locale] across the app immediately. Persists in two places:
     *   - AppCompatDelegate (the primary, OS-aware path)
     *   - Our own SharedPreferences (the fallback, applied at next start)
     *
     * Safe to call from any thread — AppCompatDelegate hops to the main
     * thread internally to trigger Activity recreation where required.
     */
    fun apply(context: Context, locale: AppLocale) {
        Timber.i("LocaleManager.apply(%s) — primary path via AppCompatDelegate", locale.tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.tag))
        prefs(context).edit().putString(KEY_CHOSEN_TAG, locale.tag).apply()
    }

    /**
     * Re-apply the user's stored choice at app start. No-op if the user
     * has never chosen (first-launch gate handles that path), or if the
     * AppCompatDelegate state already matches.
     */
    fun restoreFromPreferences(context: Context) {
        val storedTag = prefs(context).getString(KEY_CHOSEN_TAG, null) ?: return
        val stored = AppLocale.fromTag(storedTag) ?: return
        val applied = current()
        if (applied == stored) {
            Timber.d("LocaleManager.restore: already %s, skipping", stored.tag)
            return
        }
        Timber.i("LocaleManager.restore: applying stored %s (was %s)", stored.tag, applied?.tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored.tag))
    }

    fun hasConfirmedFirstLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    fun markFirstLaunchConfirmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
