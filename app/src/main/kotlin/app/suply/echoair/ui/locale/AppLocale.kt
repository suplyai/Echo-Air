package app.suply.echoair.ui.locale

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
 * AppCompatDelegate.setApplicationLocales is the single source of truth.
 * On API 33+ the OS surfaces it under Settings → Apps → Echo Air → Language;
 * on API 30–32 the AppCompat library persists and applies it per-process.
 * [LocaleManager] layers two small things on top of the platform call:
 *   1. A SharedPreferences flag tracking whether the user has been through
 *      the first-launch confirmation. Without this we'd re-prompt every
 *      time the user opens the app after a reboot.
 *   2. A normalised current() accessor so the UI can pick the right radio
 *      in the language sheet without dealing with null-list edge cases.
 */
object LocaleManager {

    private const val PREFS = "echoair_locale"
    private const val KEY_FIRST_LAUNCH_DONE = "first_launch_confirmed"

    /** The language currently applied to the app, or null if the OS is using its default. */
    fun current(): AppLocale? {
        val list = AppCompatDelegate.getApplicationLocales()
        if (list.isEmpty) return null
        return AppLocale.fromTag(list.get(0)?.toLanguageTag())
    }

    /**
     * Apply [locale] across the app immediately. Safe to call from any
     * thread — AppCompatDelegate hops to the main thread internally to
     * trigger Activity recreation where required.
     */
    fun apply(locale: AppLocale) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.tag))
    }

    fun hasConfirmedFirstLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    fun markFirstLaunchConfirmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
