package app.suply.echoair.ui.awb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.domain.Awb
import app.suply.echoair.ui.capture.CaptureViewModel
import app.suply.echoair.ui.capture.failureCopy

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
    val haptics = LocalHapticFeedback.current
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

    // Auto-advance from prefix to serial once prefix fills.
    LaunchedEffect(prefix) {
        if (prefix.length == 3) serialFocus.requestFocus()
    }
    LaunchedEffect(Unit) { prefixFocus.requestFocus() }

    // Success → bounce out to Collection.
    state.shipment?.let { dto ->
        LaunchedEffect(dto.id) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onShipmentReady(dto.id)
            vm.clear()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enter AWB") },
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
                "Enter the 11-digit air waybill number",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "3-digit airline prefix, then 8-digit serial.",
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
                        validAwb?.let { submit(it, vm, haptics) }
                    },
                    modifier = Modifier
                        .weight(0.65f)
                        .focusRequester(serialFocus)
                )
            }

            if (checkDigitMismatch) {
                val expected = Awb.expectedCheckDigit(serial)
                Text(
                    "Check digit doesn't match — double-check the last digit" +
                        (expected?.let { " (expected $it)" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { validAwb?.let { submit(it, vm, haptics) } },
                enabled = validAwb != null && !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (state.loading) CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                ) else Text("Continue", style = MaterialTheme.typography.titleMedium)
            }
        }

        state.failure?.let { failure ->
            val (title, body) = failureCopy(failure)
            AlertDialog(
                onDismissRequest = { vm.clear() },
                confirmButton = { TextButton(onClick = { vm.clear() }) { Text("OK") } },
                title = { Text(title) },
                text = { Text(body) }
            )
        }
    }
}

private fun submit(
    awb: String,
    vm: CaptureViewModel,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    vm.identifyByAwb(awb)
}

@Composable
private fun DigitField(
    value: String,
    onValueChange: (String) -> Unit,
    length: Int,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    onImeAction: (() -> Unit)? = null
) {
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
