package app.suply.echoair.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.suply.echoair.BuildConfig
import app.suply.echoair.R
import app.suply.echoair.ui.locale.AppLocale
import app.suply.echoair.ui.locale.LanguagePickerSheet
import app.suply.echoair.ui.locale.LocaleManager
import app.suply.echoair.ui.locale.findActivity

/**
 * Pilot-shape home screen: two CTAs only, plus a subtle language
 * switcher (globe icon). See strings.xml and each values-LANG/strings.xml
 * for every user-visible string rendered here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScanQr: () -> Unit,
    onEnterAwb: () -> Unit,
    onOpenSettings: () -> Unit,
    onScanDocumentDebug: () -> Unit,
) {
    var showLanguageSheet by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = { showLanguageSheet = true }) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = stringResource(R.string.home_language_cd)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.home_settings_cd)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScanQr() },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            stringResource(R.string.home_cta_scan_qr_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        stringResource(R.string.home_cta_scan_qr_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            OutlinedButton(
                onClick = onEnterAwb,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.home_cta_enter_awb),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (BuildConfig.DEBUG) {
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                TextButton(
                    onClick = onScanDocumentDebug,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.home_debug_scan_document)) }
            }
        }
    }

    if (showLanguageSheet) {
        LanguagePickerSheet(
            onSelect = { locale ->
                showLanguageSheet = false
                LocaleManager.apply(appContext, locale)
                // Force Activity recreate() so attachBaseContext re-reads the
                // stored tag and wraps Resources with the new Locale — without
                // this the visible strings don't swap until next cold start.
                context.findActivity()?.recreate()
            },
            onDismiss = { showLanguageSheet = false }
        )
    }
}
