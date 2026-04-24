package app.suply.echoair.ui.locale

import android.content.res.Configuration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.suply.echoair.R

/**
 * First-launch language confirmation. Renders once per install: the app
 * detects the device locale, maps it to a supported [AppLocale] if any,
 * and asks the user "We've set your language to <Native Name>. Is this
 * correct?" in that language.
 *
 * "Yes, continue"    → mark first-launch confirmed, dismiss.
 * "Change language"  → open the LanguagePickerSheet instead.
 * Unsupported device locale → defaults to English, shows the sheet
 *                             directly (no confirmation — nothing to
 *                             confirm).
 */
@Composable
fun FirstLaunchLanguageGate(
    onAcknowledged: () -> Unit
) {
    val context = LocalContext.current
    val detected = remember { AppLocale.fromSystemDefault() }

    val appContext = context.applicationContext

    // If the device is already on an unsupported language, quietly default
    // to English and bring up the picker directly. No confirmation prompt
    // because the user has no established expectation to confirm against.
    if (detected == null) {
        var showPicker by remember { mutableStateOf(true) }
        LocaleManager.apply(appContext, AppLocale.DEFAULT)
        if (showPicker) {
            LanguagePickerSheet(
                onSelect = { picked ->
                    showPicker = false
                    LocaleManager.apply(appContext, picked)
                    LocaleManager.markFirstLaunchConfirmed(context)
                    onAcknowledged()
                    context.findActivity()?.recreate()
                },
                onDismiss = {
                    showPicker = false
                    LocaleManager.markFirstLaunchConfirmed(context)
                    onAcknowledged()
                }
            )
        }
        return
    }

    // Apply the detected locale so the dialog itself renders in the user's
    // own language — can't confirm a choice in a language you don't read.
    LocaleManager.apply(appContext, detected)

    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        LanguagePickerSheet(
            onSelect = { picked ->
                showPicker = false
                LocaleManager.apply(appContext, picked)
                LocaleManager.markFirstLaunchConfirmed(context)
                onAcknowledged()
                context.findActivity()?.recreate()
            },
            onDismiss = { showPicker = false }
        )
        return
    }

    // Title + body rendered in the detected language via a locale-overridden
    // Resources — the AppCompat locale apply above triggers Activity
    // recreation asynchronously, but we want the dialog's text to reflect the
    // detected language on this first render regardless of apply timing.
    val localisedNativeName = remember(detected) {
        val cfg = Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(detected.tag))
        }
        val res = context.createConfigurationContext(cfg).resources
        res.getString(res.getIdentifier(detected.nativeNameKey, "string", context.packageName))
    }

    AlertDialog(
        onDismissRequest = { /* modal; user must pick */ },
        title = { Text(stringResourceIn(context, detected, R.string.language_first_launch_question)) },
        text = {
            Text(
                text = stringResourceIn(
                    context, detected,
                    R.string.language_first_launch_set_to,
                    localisedNativeName
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                LocaleManager.markFirstLaunchConfirmed(context)
                onAcknowledged()
                // prefs already written at the top of the gate; recreate()
                // forces attachBaseContext to re-wrap Resources with the
                // detected locale so the home screen renders in it.
                context.findActivity()?.recreate()
            }) {
                Text(stringResourceIn(context, detected, R.string.language_first_launch_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = { showPicker = true }) {
                Text(stringResourceIn(context, detected, R.string.language_first_launch_change))
            }
        }
    )
}

private fun stringResourceIn(
    context: android.content.Context,
    locale: AppLocale,
    resId: Int,
    vararg args: Any
): String {
    val cfg = Configuration(context.resources.configuration).apply {
        setLocale(java.util.Locale.forLanguageTag(locale.tag))
    }
    val res = context.createConfigurationContext(cfg).resources
    return if (args.isEmpty()) res.getString(resId) else res.getString(resId, *args)
}
