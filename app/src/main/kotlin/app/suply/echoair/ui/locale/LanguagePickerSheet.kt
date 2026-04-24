package app.suply.echoair.ui.locale

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.suply.echoair.R

/**
 * The language picker is the one place in the app where every row must
 * render in its own native script regardless of which locale the rest
 * of the UI is currently using ("English", "Español", "简体中文",
 * "日本語"). A user who's accidentally landed on a language they can't
 * read must still be able to identify their own.
 *
 * We get the native name of each [AppLocale] by building a locale-
 * specific [Resources] via [Configuration] and reading the
 * corresponding string resource from that Resources instance. This
 * avoids hard-coding the native names in a Kotlin constant (they'd drift
 * from the strings.xml source of truth).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    onSelect: (AppLocale) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val current = remember { LocaleManager.current() }
    val nativeNames = remember {
        AppLocale.entries.associateWith { locale ->
            nativeName(context, locale)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = context.resources.getString(R.string.language_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            AppLocale.entries.forEach { locale ->
                LanguageRow(
                    native = nativeNames[locale] ?: locale.name,
                    selected = current == locale,
                    onClick = { onSelect(locale) }
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(native: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = native,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 12.dp)
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Spacer(Modifier.width(20.dp))
        }
    }
}

/** Reads [AppLocale.nativeNameKey] from a locale-overridden Context so
 *  the returned string is always in the target language, not the current one. */
private fun nativeName(context: android.content.Context, locale: AppLocale): String {
    val config = Configuration(context.resources.configuration).apply {
        setLocale(java.util.Locale.forLanguageTag(locale.tag))
    }
    val localisedResources = context.createConfigurationContext(config).resources
    val resId = context.resources.getIdentifier(locale.nativeNameKey, "string", context.packageName)
        .takeIf { it != 0 }
        ?: return locale.tag
    return localisedResources.getString(resId)
}
