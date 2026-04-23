package app.suply.echoair.ui.collection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R
import app.suply.echoair.ble.CollectionOrchestrator.Device
import app.suply.echoair.ble.CollectionOrchestrator.DeviceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    shipmentId: String,
    onClose: () -> Unit,
    vm: CollectionViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val shipment by vm.shipment.collectAsState()
    val context = LocalContext.current

    val blePerms = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // FINE_LOCATION is required for BLE scanning on every Android
            // version in practice — pre-12 needs it by spec, and some OEMs
            // (Honor / Huawei / Xiaomi) silently require it on 12+ too.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.start(shipmentId)
    }

    LaunchedEffect(shipmentId) {
        val granted = blePerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) vm.start(shipmentId) else permLauncher.launch(blePerms)
    }

    var confirmClose by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shipment?.awbNumber ?: "Collecting") },
                navigationIcon = {
                    IconButton(onClick = { confirmClose = true }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            )
        },
        bottomBar = {
            BottomBar(
                collected = state.collectedCount,
                total = state.totalCount,
                allScanned = state.allScanned || (state.totalCount > 0 && state.collectedCount == state.totalCount),
                onFinish = onClose,
                onClosePartial = { confirmClose = true }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ShipmentHeader(
                awb = shipment?.awbNumber ?: "",
                origin = shipment?.originIata,
                destination = shipment?.destIata,
                commodity = shipment?.commodityName,
                minTemp = shipment?.commodityMinTemp,
                maxTemp = shipment?.commodityMaxTemp,
                collected = state.collectedCount,
                total = state.totalCount
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.devices, key = { it.deviceId }) { device ->
                    DeviceRow(device, onRetry = { vm.retry(device.deviceId) })
                }
            }
        }
    }

    if (confirmClose) {
        val remaining = state.totalCount - state.collectedCount
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(if (remaining > 0) "Close without $remaining device${if (remaining == 1) "" else "s"}?" else "Finish shipment?") },
            text = {
                Text(
                    if (remaining > 0)
                        "Missing devices will be recorded in the attestation pack."
                    else "All expected devices have been collected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.markPartialAndStop()
                    confirmClose = false
                    onClose()
                }) { Text("Close shipment") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.capture_cancel)) }
            }
        )
    }
}

@Composable
private fun ShipmentHeader(
    awb: String,
    origin: String?,
    destination: String?,
    commodity: String?,
    minTemp: Double?,
    maxTemp: Double?,
    collected: Int,
    total: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(awb, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        val route = listOfNotNull(origin, destination).joinToString(" → ")
        if (route.isNotBlank()) Text(route, style = MaterialTheme.typography.bodyMedium)

        val profile = listOfNotNull(
            commodity,
            if (minTemp != null && maxTemp != null) "${minTemp}–${maxTemp}°C" else null
        ).joinToString(", ")
        if (profile.isNotBlank()) Text(profile, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(4.dp))
        Text(
            "${stringResource(R.string.collection_collected)}: $collected ${stringResource(R.string.collection_of)} $total",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeviceRow(device: Device, onRetry: () -> Unit) {
    val progress by animateFloatAsState(targetValue = device.progress, label = "deviceProgress")
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateIndicator(state = device.state, progress = progress)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Device ${device.deviceId}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stateLabel(device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (device.state == DeviceState.ERROR) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.collection_retry)) }
                }
            }

            if (device.state == DeviceState.SYNCING) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }

            val detailParts = buildList {
                device.lastTemp?.let { add("%.1f°C".format(it)) }
                device.lastHumidity?.let { add("%.0f%%".format(it)) }
                device.batteryMv?.let { add("${it / 1000.0} V") }
                device.rssi?.let { add("${it} dBm") }
                device.recordCount?.let { add("$it records") }
                if (device.tempMin != null && device.tempMax != null) {
                    add("%.1f–%.1f°C range".format(device.tempMin, device.tempMax))
                }
            }
            if (detailParts.isNotEmpty()) {
                Text(
                    detailParts.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            device.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (device.alarm) {
                Text("Alarm on device", style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.state_error))
            }
        }
    }
}

@Composable
private fun StateIndicator(state: DeviceState, progress: Float) {
    val (icon: ImageVector, color: Color) = when (state) {
        DeviceState.SEARCHING -> Icons.Default.BluetoothSearching to colorResource(R.color.state_searching)
        DeviceState.IN_RANGE -> Icons.Default.BluetoothSearching to colorResource(R.color.state_in_range)
        DeviceState.SYNCING -> Icons.Default.Sync to colorResource(R.color.state_syncing)
        DeviceState.COLLECTED -> Icons.Default.CheckCircle to colorResource(R.color.state_collected)
        DeviceState.MISSING -> Icons.Default.WarningAmber to colorResource(R.color.state_missing)
        DeviceState.ERROR -> Icons.Default.ErrorOutline to colorResource(R.color.state_error)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color)
    }
}

@Composable
private fun stateLabel(device: Device): String = when (device.state) {
    DeviceState.SEARCHING -> stringResource(R.string.collection_searching)
    DeviceState.IN_RANGE -> stringResource(R.string.collection_in_range)
    DeviceState.SYNCING -> "${stringResource(R.string.collection_syncing)}  ${(device.progress * 100).toInt()}%"
    DeviceState.COLLECTED -> stringResource(R.string.collection_collected_state)
    DeviceState.MISSING -> stringResource(R.string.collection_missing)
    DeviceState.ERROR -> stringResource(R.string.collection_error)
}

@Composable
private fun BottomBar(
    collected: Int,
    total: Int,
    allScanned: Boolean,
    onFinish: () -> Unit,
    onClosePartial: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val remaining = total - collected
            if (allScanned) {
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.collection_finish))
                }
            } else {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("$remaining more device${if (remaining == 1) "" else "s"} to find")
                }
                TextButton(onClick = onClosePartial, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.collection_close_partial))
                }
            }
        }
    }
}
