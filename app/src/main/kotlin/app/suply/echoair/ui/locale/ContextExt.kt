package app.suply.echoair.ui.locale

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * Returns a Context whose Resources are forced to [locale], without
 * touching the global default Locale. Used in Activity.attachBaseContext
 * so every stringResource / pluralStringResource lookup inside that
 * Activity's composition tree resolves against the user's chosen
 * language.
 */
fun Context.wrapForLocale(locale: AppLocale): Context {
    val overlay = Configuration(resources.configuration).apply {
        setLocale(Locale.forLanguageTag(locale.tag))
    }
    return createConfigurationContext(overlay)
}

/**
 * Walks ContextWrapper chain to find the hosting Activity. Needed
 * because [androidx.compose.ui.platform.LocalContext] can return a
 * ContextThemeWrapper that isn't itself an Activity.
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
