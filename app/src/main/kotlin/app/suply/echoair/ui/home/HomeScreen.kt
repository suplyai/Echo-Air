package app.suply.echoair.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.suply.echoair.R

/**
 * Stateless landing page. No login, no shipment list, no auth-gated content
 * — the user is here to scan one device and walk away. The two CTAs
 * (document camera + device QR) both drop straight into [CaptureScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScanDocument: () -> Unit,
    onScanDeviceQr: () -> Unit,
    onOpenSettings: () -> Unit
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScanDocument() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.home_cta),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Text(
                        stringResource(R.string.home_cta_sub),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            OutlinedButton(onClick = onScanDeviceQr, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_scan_qr))
            }
        }
    }
}
