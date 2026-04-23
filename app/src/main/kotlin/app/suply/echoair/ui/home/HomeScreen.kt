package app.suply.echoair.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.suply.echoair.BuildConfig
import app.suply.echoair.R

/**
 * Pilot-shape home screen: two CTAs only.
 *   - Scan QR code (primary) — handles both device QRs and label QRs
 *     encoding an AWB, routed inside the scanner.
 *   - Enter AWB manually (secondary) — structured 3+8 digit input with
 *     mod-7 check-digit validation.
 *
 * The OCR "Scan document" path is intentionally hidden from pilot builds:
 * confident-looking misreads produce real-looking errors, which is the
 * wrong first impression for a consignee. It stays reachable from this
 * screen in debug builds so we can keep iterating on it internally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScanQr: () -> Unit,
    onEnterAwb: () -> Unit,
    onOpenSettings: () -> Unit,
    onScanDocumentDebug: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_settings))
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
                            "Scan QR code",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "On the Echo Air device, or on the shipment label.",
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
                Text("Enter AWB manually", style = MaterialTheme.typography.titleMedium)
            }

            if (BuildConfig.DEBUG) {
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                TextButton(
                    onClick = onScanDocumentDebug,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("[debug] Scan document (OCR)") }
            }
        }
    }
}
