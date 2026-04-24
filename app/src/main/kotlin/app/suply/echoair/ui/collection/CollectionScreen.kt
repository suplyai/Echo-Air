package app.suply.echoair.ui.collection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R
import app.suply.echoair.ble.CollectionOrchestrator.Device
import app.suply.echoair.ble.CollectionOrchestrator.DeviceState
import app.suply.echoair.ui.haptics.EchoHaptics

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
                title = { Text(shipment?.awbNumber ?: stringResource(R.string.collection_title_fallback)) },
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
            title = {
                Text(
                    if (remaining > 0)
                        pluralStringResource(
                            R.plurals.collection_close_dialog_title_partial,
                            remaining,
                            remaining
                        )
                    else stringResource(R.string.collection_close_dialog_title_finish)
                )
            },
            text = {
                Text(
                    if (remaining > 0)
                        stringResource(R.string.collection_close_dialog_body_partial)
                    else stringResource(R.string.collection_close_dialog_body_all)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.markPartialAndStop()
                    confirmClose = false
                    onClose()
                }) { Text(stringResource(R.string.collection_close_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
            "${stringResource(R.string.collection_header_collected)}: $collected ${stringResource(R.string.collection_header_of)} $total",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeviceRow(device: Device, onRetry: () -> Unit) {
    val progress by animateFloatAsState(targetValue = device.progress, label = "deviceProgress")
    val appContext = LocalContext.current.applicationContext

    // State-entry haptics — soft tap when the device first comes into range,
    // firm tick when collection completes. Keyed on state so they each fire
    // exactly once per transition.
    LaunchedEffect(device.state) {
        when (device.state) {
            DeviceState.IN_RANGE -> EchoHaptics.softTap(appContext)
            DeviceState.COLLECTED -> EchoHaptics.tick(appContext)
            else -> Unit
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateIndicator(state = device.state, progress = progress)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.collection_row_device_id, device.deviceId),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stateLabel(device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (device.state == DeviceState.ERROR) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.collection_row_retry)) }
                }
            }

            // Progress bar is indeterminate while connecting (progress == 0)
            // or finalising (progress >= 1.0) — both are "work happening but
            // nothing new to report" states, and the shimmer keeps the screen
            // feeling alive. Determinate fill in between.
            if (device.state == DeviceState.SYNCING) {
                val indeterminate = progress <= 0.001f || progress >= 0.999f
                if (indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                }
            }

            val detailParts = buildList {
                device.lastTemp?.let { add("%.1f°C".format(it)) }
                device.lastHumidity?.let { add("%.0f%%".format(it)) }
                // Prefer percent + voltage together — "100% (3.08 V)". Falls
                // back to either alone if the other is null.
                val percent = device.batteryPercent
                val volts = device.batteryMv?.let { "%.2f V".format(it / 1000.0) }
                when {
                    percent != null && volts != null -> add("$percent% ($volts)")
                    percent != null -> add("$percent%")
                    volts != null -> add(volts)
                }
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
                Text(stringResource(R.string.collection_alarm_on_device), style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.state_error))
            }
        }
    }
}

/**
 * Animated state indicator. Continuously alive through every async state so
 * the screen never reads as "hung" during the 2–5 second invisible windows
 * (BLE scan wait, GATT negotiation, /api/echo-scan POST).
 *
 * - SEARCHING: slow concentric radar-pulse ring behind a static glyph.
 *   Loops forever at ~1.6s period. Stops the moment state changes.
 * - IN_RANGE: one-shot 1.0 → 1.15 → 1.0 scale pulse + colour nudge; haptic
 *   fires on state entry via LaunchedEffect in DeviceRow.
 * - SYNCING: subtle breathing opacity on the background, ~1.8s period.
 *   Rotating Sync icon if progress hasn't started yet; the icon holds
 *   still once real records are flowing (the progress bar carries the
 *   motion then, so doubling up reads as frantic).
 * - COLLECTED: on state entry, a glow ring briefly expands and fades.
 *   Haptic fires via LaunchedEffect in DeviceRow. Settles into the
 *   static check circle.
 */
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

    val transition = rememberInfiniteTransition(label = "indicatorAmbient")

    // SEARCHING: radar ring scales 1.0 → 1.8 and fades 0.45 → 0.0.
    val radarScale by transition.animateFloat(
        initialValue = 1.0f, targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "radarScale"
    )
    val radarAlpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "radarAlpha"
    )

    // SYNCING (pre-progress): background alpha breathes 0.12 → 0.25.
    val syncingBreath by transition.animateFloat(
        initialValue = 0.12f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "syncingBreath"
    )

    // SYNCING (pre-progress): sync glyph spins. Stops once real progress lands
    // so the progress bar becomes the sole motion channel.
    val syncRotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "syncRotation"
    )

    // IN_RANGE: one-shot acknowledgment pulse on entry.
    val inRangePulse by animateFloatAsState(
        targetValue = if (state == DeviceState.IN_RANGE) 1.15f else 1.0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "inRangePulse"
    )

    // COLLECTED: one-shot scale bump on entry.
    val collectedBump by animateFloatAsState(
        targetValue = if (state == DeviceState.COLLECTED) 1.0f else 0.9f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "collectedBump"
    )

    val backgroundAlpha by animateColorAsState(
        targetValue = color.copy(
            alpha = when {
                state == DeviceState.SYNCING && progress <= 0.001f -> syncingBreath
                state == DeviceState.COLLECTED -> 0.18f
                else -> 0.12f
            }
        ),
        animationSpec = tween(200),
        label = "indicatorBg"
    )

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        // SEARCHING radar ring — rendered behind the main circle.
        if (state == DeviceState.SEARCHING) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = radarScale
                        scaleY = radarScale
                        alpha = radarAlpha
                    }
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.55f))
            )
        }

        // COLLECTED glow ring — briefly expands and fades when the tick lands.
        if (state == DeviceState.COLLECTED) {
            val glowScale by animateFloatAsState(
                targetValue = if (state == DeviceState.COLLECTED) 1.6f else 1.0f,
                animationSpec = tween(480, easing = FastOutSlowInEasing),
                label = "collectedGlowScale"
            )
            val glowAlpha by animateFloatAsState(
                targetValue = if (state == DeviceState.COLLECTED) 0.0f else 0.4f,
                animationSpec = tween(480, easing = FastOutSlowInEasing),
                label = "collectedGlowAlpha"
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = glowScale
                        scaleY = glowScale
                        alpha = glowAlpha
                    }
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.55f))
            )
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .scale(
                    when (state) {
                        DeviceState.IN_RANGE -> inRangePulse
                        DeviceState.COLLECTED -> collectedBump
                        else -> 1.0f
                    }
                )
                .clip(CircleShape)
                .background(backgroundAlpha),
            contentAlignment = Alignment.Center
        ) {
            val iconModifier = if (state == DeviceState.SYNCING && progress <= 0.001f) {
                Modifier.graphicsLayer { rotationZ = syncRotation }
            } else {
                Modifier
            }
            Icon(icon, contentDescription = null, tint = color, modifier = iconModifier)
        }
    }
}

@Composable
private fun stateLabel(device: Device): String = when (device.state) {
    DeviceState.SEARCHING -> stringResource(R.string.collection_state_searching)
    DeviceState.IN_RANGE -> stringResource(R.string.collection_state_in_range)
    DeviceState.SYNCING -> when {
        device.progress <= 0.001f -> stringResource(R.string.collection_state_connecting)
        device.progress >= 0.999f -> stringResource(R.string.collection_state_finalising)
        else -> stringResource(
            R.string.collection_state_syncing_with_percent,
            (device.progress * 100).toInt()
        )
    }
    DeviceState.COLLECTED -> stringResource(R.string.collection_state_collected)
    DeviceState.MISSING -> stringResource(R.string.collection_state_missing)
    DeviceState.ERROR -> stringResource(R.string.collection_state_error)
}

/**
 * Sticky state-aware bottom bar. Always visible — never scrolls out of view.
 * Transforms in place between the two states:
 *
 *   IN-PROGRESS (N remaining, or total still unknown):
 *     - Muted surface-variant background, onSurfaceVariant text.
 *     - Reads as STATUS, not ACTION: "N more devices to find" or
 *       "Finding devices…" (pre-load).
 *     - Non-tappable. The secondary "Close shipment without remaining
 *       devices" link sits below as a text-only affordance for the
 *       genuinely-lost-device escape hatch.
 *
 *   ALL-COLLECTED:
 *     - Background crossfades to primary over 200ms.
 *     - Text becomes the CTA ("All devices collected — finish").
 *     - One-shot 1.0 → 1.05 → 1.0 scale pulse (~300ms) on the state flip.
 *     - Firm [EchoHaptics.tick] on the same flip, matching the
 *       DEVICE_COLLECTED haptic — "the moment registers".
 *     - Now tappable; taps fire [onFinish].
 *     - Close-partial link hides — there's nothing to close without.
 *
 * Pulse + haptic only fire on the !allScanned → allScanned *transition* —
 * initial composition with allScanned already true (e.g. after rotation)
 * stays quiet.
 */
@Composable
private fun BottomBar(
    collected: Int,
    total: Int,
    allScanned: Boolean,
    onFinish: () -> Unit,
    onClosePartial: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val remaining = (total - collected).coerceAtLeast(0)

    val pulseScale = remember { Animatable(1f) }
    var previouslyAllScanned by remember { mutableStateOf(allScanned) }
    LaunchedEffect(allScanned) {
        if (allScanned && !previouslyAllScanned) {
            EchoHaptics.tick(context)
            pulseScale.animateTo(1.05f, tween(150, easing = FastOutSlowInEasing))
            pulseScale.animateTo(1.0f, tween(150, easing = FastOutSlowInEasing))
        }
        previouslyAllScanned = allScanned
    }

    val barColor by animateColorAsState(
        targetValue = if (allScanned) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "bottomBarBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (allScanned) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "bottomBarText"
    )

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = pulseScale.value
                        scaleY = pulseScale.value
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(barColor)
                    .then(
                        if (allScanned) Modifier.clickable(onClick = onFinish)
                        else Modifier
                    )
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        allScanned -> stringResource(R.string.collection_finish)
                        total > 0 -> pluralStringResource(
                            R.plurals.collection_more_devices_to_find,
                            remaining,
                            remaining
                        )
                        else -> stringResource(R.string.collection_finding_devices)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    fontWeight = if (allScanned) FontWeight.SemiBold else FontWeight.Medium
                )
            }
            if (!allScanned) {
                TextButton(
                    onClick = onClosePartial,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.collection_close_partial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
