package app.suply.echoair.ui.collection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import timber.log.Timber

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
                originIata = shipment?.originIata,
                originCity = shipment?.originCity,
                destIata = shipment?.destIata,
                destCity = shipment?.destCity,
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

/**
 * Shipment identity header. The AWB is intentionally absent from this
 * block — it's already pinned in the TopAppBar title; showing it twice
 * within the same few vertical centimetres (as in v0.4.4 and earlier)
 * was pure noise. Instead the header now carries the information a
 * consignee actually needs to orient themselves at destination:
 *
 *   Mixed Cut Flowers                ← commodity (titleLarge)
 *   Lima (LIM) → Amsterdam (AMS)    ← route w/ city names
 *   -20 – 2°C                        ← temp profile, if present
 *   Collected: 0 of 2                ← always shown
 *
 * Every row above the count degrades gracefully — if the shipment has
 * no commodity / no route / no temp bounds, those rows are simply
 * omitted. Worst case (all unknown) the user still sees "Collected:
 * N of M", which is the one line they always need.
 *
 * City names come from the unified shipment helper
 * (air_origin_city / air_dest_city in ShipmentDto). IATA codes render
 * alone as a fallback when city is missing.
 */
@Composable
private fun ShipmentHeader(
    originIata: String?,
    originCity: String?,
    destIata: String?,
    destCity: String?,
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
        commodity?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        val originStr = formatAirport(originIata, originCity)
        val destStr = formatAirport(destIata, destCity)
        val route = listOfNotNull(originStr, destStr).joinToString(" → ")
        if (route.isNotBlank()) {
            Text(route, style = MaterialTheme.typography.bodyMedium)
        }

        if (minTemp != null && maxTemp != null) {
            Text(
                "${minTemp}–${maxTemp}°C",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "${stringResource(R.string.collection_header_collected)}: $collected ${stringResource(R.string.collection_header_of)} $total",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * "Lima (LIM)" when both are present, "LIM" or "Lima" when only one is,
 * null when neither — lets the caller skip the row entirely on null.
 */
private fun formatAirport(code: String?, city: String?): String? {
    val c = code?.takeIf { it.isNotBlank() }
    val n = city?.takeIf { it.isNotBlank() }
    return when {
        n != null && c != null -> "$n ($c)"
        c != null -> c
        n != null -> n
        else -> null
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

    // Per-device proximity-hint level (0 = none, 1/2/3 progressively
    // stronger). Driven by elapsed SEARCHING time — see [searchHintLevel].
    val hintLevel = rememberSearchingHintLevel(device)

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

            // Proximity-hint region. Visible for the entire duration of
            // SEARCHING — L0 is an ambient, preemptive prompt ("Hold
            // your phone near the shipment") that appears immediately,
            // escalating in place at 5 / 20 / 60 s. Collapses smoothly
            // via expand/shrinkVertically the moment we leave SEARCHING.
            AnimatedVisibility(
                visible = device.state == DeviceState.SEARCHING,
                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                SearchingHint(level = hintLevel)
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
 * - SEARCHING: three concentric radar rings emanate outward, staggered
 *   600ms apart over an 1800ms loop (Find My-style sweep — conveys
 *   "reaching out into the environment"). On top of that, the Bluetooth
 *   glyph breathes 1.0 → 1.03 → 1.0 over a 1500ms cycle so the icon
 *   itself reads as alive, not just the container.
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

    // SEARCHING: bluetooth glyph breathes 1.0 → 1.03 → 1.0 over 1500ms so
    // the icon itself reads alive, not just the rings around it.
    val searchBreath by transition.animateFloat(
        initialValue = 1.0f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "searchBreath"
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
        // SEARCHING: three radar rings, phase-offset 0/600/1200ms within
        // an 1800ms loop, continuously emanating outward from behind the
        // main circle. Each ring scales 1.0 → 1.8 while fading to zero —
        // with three live at once, at any instant there's always a ring
        // mid-sweep, so the motion reads as continuous.
        if (state == DeviceState.SEARCHING) {
            RadarRing(color = color, transition = transition, offsetMs = 0)
            RadarRing(color = color, transition = transition, offsetMs = 600)
            RadarRing(color = color, transition = transition, offsetMs = 1200)
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
            val iconModifier = when {
                state == DeviceState.SYNCING && progress <= 0.001f ->
                    Modifier.graphicsLayer { rotationZ = syncRotation }
                state == DeviceState.SEARCHING ->
                    Modifier.graphicsLayer {
                        scaleX = searchBreath
                        scaleY = searchBreath
                    }
                else -> Modifier
            }
            Icon(icon, contentDescription = null, tint = color, modifier = iconModifier)
        }
    }
}

/**
 * One radar-sweep ring. Scales 1.0 → 1.8 while fading from 0.45 → 0 over
 * an 1800ms linear loop, with [offsetMs] shifting its phase so a trio of
 * these (at 0 / 600 / 1200ms) produces a continuous outward-radiating
 * sweep rather than three synchronised pulses.
 */
@Composable
private fun RadarRing(color: Color, transition: InfiniteTransition, offsetMs: Int) {
    val scale by transition.animateFloat(
        initialValue = 1.0f, targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(offsetMs)
        ),
        label = "radarScale_$offsetMs"
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(offsetMs)
        ),
        label = "radarAlpha_$offsetMs"
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = ringAlpha
            }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.55f))
    )
}

// Proximity-hint thresholds, in elapsed SEARCHING milliseconds.
//
// Level 0 is the always-on ambient prompt ("Hold your phone near the
// shipment.") — no timer; it's visible from the first frame of
// SEARCHING, on the theory that preemptive guidance beats reactive
// guidance, and that v0.4.4's 15s silence was long enough for users
// to start doubting whether the app was working at all.
//
// Levels 1/2/3 escalate from there. Tuned from field feedback; kept
// together here so a single-line edit retunes them. Timber.d fires on
// each level reached so we can measure in the field whether 5s is
// still too eager / 60s too patient.
private const val HINT_LEVEL_1_MS = 5_000L    // "Move closer — devices are usually inside the ULD."
private const val HINT_LEVEL_2_MS = 20_000L   // "Still searching. Check the device is inside the cargo and powered."
private const val HINT_LEVEL_3_MS = 60_000L   // "Can't find this device. Contact your shipper if it may be missing."

/**
 * Per-device proximity-hint level driven by how long the device has been
 * SEARCHING. Returns 0 while the state isn't SEARCHING or while we're
 * inside the first quiet window; 1/2/3 as each threshold is crossed.
 *
 * Time source is [Device.searchStartedAt] (wall-clock ms), set by the
 * orchestrator when the device enters SEARCHING. This means the hint
 * level survives Activity recreation / rotation — if the user rotates
 * after 20s of searching, the hint shows on reappearance rather than
 * restarting the 15s timer from zero.
 */
@Composable
private fun rememberSearchingHintLevel(device: Device): Int {
    var level by remember(device.deviceId) { mutableIntStateOf(0) }
    LaunchedEffect(device.deviceId, device.state, device.searchStartedAt) {
        if (device.state != DeviceState.SEARCHING || device.searchStartedAt <= 0L) {
            level = 0
            return@LaunchedEffect
        }
        val thresholds = longArrayOf(HINT_LEVEL_1_MS, HINT_LEVEL_2_MS, HINT_LEVEL_3_MS)
        // Fast-forward if already past a threshold (e.g. after rotation).
        val alreadyElapsed = System.currentTimeMillis() - device.searchStartedAt
        var initial = 0
        for ((i, t) in thresholds.withIndex()) {
            if (alreadyElapsed >= t) initial = i + 1 else break
        }
        if (initial > 0) {
            level = initial
            Timber.d(
                "search-hint L%d entered for %s (already at %dms)",
                initial, device.deviceId, alreadyElapsed
            )
        }
        // Schedule the remaining thresholds.
        for (i in initial until thresholds.size) {
            val remain = thresholds[i] - (System.currentTimeMillis() - device.searchStartedAt)
            if (remain > 0) delay(remain)
            level = i + 1
            Timber.d(
                "search-hint L%d reached for %s at %dms",
                level, device.deviceId,
                System.currentTimeMillis() - device.searchStartedAt
            )
        }
    }
    return level
}

/**
 * Renders the per-device proximity hint in one of four forms:
 *
 *   L0 (ambient, 0s+): muted grey "Hold your phone near the shipment."
 *       — preemptive guidance, visible from the first frame.
 *   L1 (5s):  warmer accent colour, the lightbulb gently pulses.
 *   L2 (20s): same colour treatment, escalated copy.
 *   L3 (60s): same colour treatment, final escalation — contact the
 *       shipper.
 *
 * Colour shift (onSurfaceVariant → primary) animates over 300ms so the
 * state change reads as a gentle shift of urgency, not a jarring swap.
 * The icon pulses only at L ≥ 1 — at L0 it's still, matching the
 * "ambient, not a hint" framing.
 */
@Composable
private fun SearchingHint(level: Int) {
    val text = when (level) {
        0 -> stringResource(R.string.collection_hint_ambient)
        1 -> stringResource(R.string.collection_hint_get_closer)
        2 -> stringResource(R.string.collection_hint_check_device)
        else -> stringResource(R.string.collection_hint_contact_shipper)
    }

    val color by animateColorAsState(
        targetValue = if (level >= 1) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "hintColor"
    )

    // Subtle pulse on the lightbulb at L ≥ 1. The transition runs
    // continuously but we only apply its value when escalated, so L0
    // stays visually still.
    val pulse = rememberInfiniteTransition(label = "hintIconPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1.0f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hintIconPulseScale"
    )
    val iconScale = if (level >= 1) pulseScale else 1.0f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(
            Icons.Outlined.Lightbulb,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
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
