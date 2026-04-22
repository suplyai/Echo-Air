package app.suply.echoair.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R
import app.suply.echoair.data.db.CachedShipment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShipment: (String) -> Unit,
    onOpenWeb: (path: String, title: String) -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val shipments by vm.activeShipments.collectAsState(initial = emptyList())

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
                    .clickable { onStartCapture() },
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onStartCapture, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_scan_qr))
                }
            }

            Text(
                stringResource(R.string.home_active_shipments),
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shipments, key = { it.id }) { s ->
                    ShipmentRow(s, onClick = { onOpenShipment(s.id) })
                }
            }

            TextButton(onClick = { onOpenWeb("/mobile/history", "History") }) {
                Text(stringResource(R.string.home_history))
            }
        }
    }
}

@Composable
private fun ShipmentRow(s: CachedShipment, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.awbNumber, style = MaterialTheme.typography.titleMedium)
            val route = listOfNotNull(s.originIata, s.destIata).joinToString(" → ")
            if (route.isNotBlank()) Text(route, style = MaterialTheme.typography.bodyMedium)
            s.commodityName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
