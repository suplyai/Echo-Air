package app.suply.echoair.ui.awb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R
import app.suply.echoair.domain.Awb
import app.suply.echoair.domain.IataCarriers
import app.suply.echoair.ui.capture.CaptureViewModel
import app.suply.echoair.ui.capture.failureCopy
import app.suply.echoair.ui.haptics.EchoHaptics

/**
 * Structured AWB-entry screen.
 *
 *   [airline 3-digit]  —  [serial 8-digit]
 *
 * Auto-advance (3→serial), paste-friendly (strip non-digits, distribute
 * across fields), numeric keyboard, monospaced digits so 11 characters
 * line up predictably. Inline mod-7 check-digit validation: Continue is
 * disabled until the full 11 digits resolve to a valid AWB, and an
 * amber hint surfaces the moment the 8th serial digit lands wrong so
 * the consignee can fix the typo without a round-trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAwbScreen(
    onBack: () -> Unit,
    onShipmentReady: (shipmentId: String) -> Unit,
    vm: CaptureViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val focusManager = LocalFocusManager.current

    var prefix by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    val prefixFocus = remember { FocusRequester() }
    val serialFocus = remember { FocusRequester() }

    val canonical = remember(prefix, serial) {
        if (prefix.length == 3 && serial.length == 8) "$prefix-$serial" else null
    }
    val validAwb = canonical?.takeIf(Awb::isValid)
    val checkDigitMismatch = serial.length == 8 && canonical != null && validAwb == null
    // Serial passes the IATA mod-7 check independently of the prefix —
    // the check digit is computed only over the first 7 digits of the
    // serial, so a structurally-valid 8-digit body is a meaningful
    // affirmation even when the airline prefix is unknown to our local
    // IATA list. Drives the body field's green ✓ confirmation.
    val serialValid = remember(serial) {
        serial.length == 8 &&
            Awb.expectedCheckDigit(serial) == serial.last().digitToInt()
    }

    // Airline lookup: resolves as soon as the 3rd prefix digit lands.
    val carrierName = remember(prefix) {
        if (prefix.length == 3) IataCarriers.carrierName(prefix) else null
    }
    val prefixComplete = prefix.length == 3
    val prefixMatched = prefixComplete && carrierName != null
    val prefixUnknown = prefixComplete && carrierName == null

    // Soft haptic the moment a prefix matches. Fires once per match event
    // (re-keyed on prefix), silent on edit-down below 3 digits and silent
    // on unknown prefixes.
    LaunchedEffect(prefix) {
        if (prefixMatched) EchoHaptics.softTap(appContext)
        if (prefixComplete) serialFocus.requestFocus()
    }
    LaunchedEffect(Unit) { prefixFocus.requestFocus() }

    // Success → show the same confirmation sheet the QR path uses, so
    // the "trust moment" is consistent across entry paths. Haptic fires
    // inside the sheet's Start scanning button.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.awb_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                stringResource(R.string.awb_heading),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.awb_subheading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DigitField(
                    value = prefix,
                    onValueChange = { raw ->
                        val digits = raw.filter(Char::isDigit)
                        // Paste-distribute: if the user pasted 11 digits into
                        // either field, split them back into the two fields.
                        if (digits.length >= 11) {
                            prefix = digits.substring(0, 3)
                            serial = digits.substring(3, 11)
                            focusManager.clearFocus()
                        } else {
                            prefix = digits.take(3)
                        }
                    },
                    length = 3,
                    imeAction = ImeAction.Next,
                    accentColour = if (prefixMatched) SUCCESS_GREEN else null,
                    modifier = Modifier
                        .weight(0.35f)
                        .focusRequester(prefixFocus)
                )
                Text(
                    "—",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DigitField(
                    value = serial,
                    onValueChange = { raw ->
                        val digits = raw.filter(Char::isDigit)
                        if (digits.length >= 11) {
                            prefix = digits.substring(0, 3)
                            serial = digits.substring(3, 11)
                            focusManager.clearFocus()
                        } else {
                            serial = digits.take(8)
                        }
                    },
                    length = 8,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        validAwb?.let { submit(it, vm, appContext) }
                    },
                    accentColour = if (serialValid) SUCCESS_GREEN else null,
                    trailingIcon = if (serialValid) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = SUCCESS_GREEN
                            )
                        }
                    } else null,
                    modifier = Modifier
                        .weight(0.65f)
                        .focusRequester(serialFocus)
                )
            }

            // Airline prefix feedback — match: green ✓ + carrier name;
            // unknown: quiet amber hint (non-blocking). Fade durations and
            // easing match the brief: 150ms reveal, 100ms hide, standard
            // Material easing, no slide/bounce.
            AnimatedVisibility(
                visible = prefixMatched,
                enter = fadeIn(tween(150, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = SUCCESS_GREEN,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        carrierName.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(
                visible = prefixUnknown,
                enter = fadeIn(tween(150, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
            ) {
                Text(
                    stringResource(R.string.awb_unknown_prefix),
                    style = MaterialTheme.typography.bodySmall,
                    color = AMBER
                )
            }

            if (checkDigitMismatch) {
                val expected = Awb.expectedCheckDigit(serial)
                Text(
                    text = if (expected != null)
                        stringResource(R.string.awb_check_digit_mismatch_expected, expected)
                    else stringResource(R.string.awb_check_digit_mismatch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { validAwb?.let { submit(it, vm, appContext) } },
                enabled = validAwb != null && !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                // Loading state surfaces both spinner AND "Looking up
                // shipment…" copy, not just a bare spinner — the zero-
                // latency feedback on tap is the whole point of the
                // affordance, and a spinner alone reads as ambient
                // motion rather than "your tap is being processed".
                // state.loading flips synchronously inside
                // CaptureViewModel.identifyByAwb before the network
                // call begins, so this lights up on the same frame
                // the user releases their finger.
                if (state.loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            stringResource(R.string.awb_continue_loading),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.awb_continue),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        state.failure?.let { failure ->
            val (title, body) = failureCopy(failure)
            AlertDialog(
                onDismissRequest = { vm.clear() },
                confirmButton = { TextButton(onClick = { vm.clear() }) { Text(stringResource(R.string.common_ok)) } },
                title = { Text(title) },
                text = { Text(body) }
            )
        }

        state.shipment?.let { dto ->
            app.suply.echoair.ui.capture.ConfirmShipmentSheet(
                shipment = dto,
                confidence = state.confidence,
                onConfirm = {
                    onShipmentReady(dto.id)
                    vm.clear()
                },
                onCancel = { vm.clear() }
            )
        }
    }
}

private fun submit(
    awb: String,
    vm: CaptureViewModel,
    appContext: android.content.Context
) {
    EchoHaptics.tick(appContext)
    vm.identifyByAwb(awb)
}

@Composable
private fun DigitField(
    value: String,
    onValueChange: (String) -> Unit,
    length: Int,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    onImeAction: (() -> Unit)? = null,
    accentColour: Color? = null,
    /** Optional trailing slot inside the OutlinedTextField — used by the
     *  body field to surface a green ✓ when 8 valid digits are entered.
     *  Null on the prefix field, which already has its own confirmation
     *  affordance (the carrier-name row beneath the inputs). */
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val defaultOutline = MaterialTheme.colorScheme.outline
    val defaultFocused = MaterialTheme.colorScheme.primary
    val unfocused by animateColorAsState(
        targetValue = accentColour ?: defaultOutline,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "digitFieldUnfocusedBorder"
    )
    val focused by animateColorAsState(
        targetValue = accentColour ?: defaultFocused,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "digitFieldFocusedBorder"
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 2.sp
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = imeAction
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onNext = { /* handled by auto-advance LaunchedEffect */ },
            onDone = { onImeAction?.invoke() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = focused,
            unfocusedBorderColor = unfocused
        ),
        trailingIcon = trailingIcon,
        placeholder = {
            Text(
                "0".repeat(length),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            )
        }
    )
}

private val SUCCESS_GREEN = Color(0xFF34C759)
private val AMBER = Color(0xFFB7791F)
