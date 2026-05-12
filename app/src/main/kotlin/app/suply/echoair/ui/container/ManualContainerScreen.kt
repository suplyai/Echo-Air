package app.suply.echoair.ui.container

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R
import app.suply.echoair.domain.Iso6346
import app.suply.echoair.ui.capture.CaptureViewModel
import app.suply.echoair.ui.capture.failureCopy
import app.suply.echoair.ui.haptics.EchoHaptics

/**
 * Ocean-reefer counterpart to [app.suply.echoair.ui.awb.ManualAwbScreen]:
 * a single-field entry for an ISO 6346 container number.
 *
 * Two-stage validation matches the spec — format gate first ([A-Z]{4}[0-9]{7}),
 * then the mod-11 check digit. Each stage has its own inline error so a typo
 * in the trailing digit reads differently from "this isn't even a container
 * number". The check-digit failure mode is the common one (one wrong digit
 * still passes the format gate) and is what the consignee actually needs to
 * notice and fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualContainerScreen(
    onBack: () -> Unit,
    onShipmentReady: (shipmentId: String) -> Unit,
    vm: CaptureViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val focusRequester = remember { FocusRequester() }

    var raw by remember { mutableStateOf("") }
    val canonical = remember(raw) { Iso6346.canonicalise(raw) }
    val wellFormed = canonical.length == 11 && Iso6346.isWellFormed(canonical)
    val valid = wellFormed && Iso6346.isValid(canonical)

    // Distinct inline-error states. Format error is suppressed until the
    // input is at least full-length so we don't yell at the user mid-type.
    val showFormatError = canonical.length == 11 && !wellFormed
    val showCheckDigitError = wellFormed && !valid
    val expectedCheckDigit = if (showCheckDigitError) {
        Iso6346.computedCheckDigit(canonical)
    } else null

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.container_entry_title)) },
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
                stringResource(R.string.container_entry_heading),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.container_entry_subheading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ContainerField(
                value = raw,
                onValueChange = { input ->
                    // Cap at 11 canonical chars so paste-with-spaces doesn't
                    // silently truncate visible input — we strip whitespace
                    // and uppercase live, which feels cleaner than letting
                    // the user type junk that the validator quietly drops.
                    val cleaned = Iso6346.canonicalise(input).take(11)
                    raw = cleaned
                },
                accentColour = if (valid) SUCCESS_GREEN else null,
                trailingIcon = if (valid) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = SUCCESS_GREEN
                        )
                    }
                } else null,
                onSubmit = { if (valid) submit(canonical, vm, appContext) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )

            AnimatedVisibility(
                visible = showFormatError,
                enter = fadeIn(tween(150, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
            ) {
                Text(
                    text = stringResource(R.string.container_entry_validation_format),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            AnimatedVisibility(
                visible = showCheckDigitError,
                enter = fadeIn(tween(150, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(100, easing = FastOutSlowInEasing))
            ) {
                Text(
                    text = if (expectedCheckDigit != null)
                        stringResource(R.string.container_entry_validation_checkdigit_expected, expectedCheckDigit)
                    else stringResource(R.string.container_entry_validation_checkdigit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { if (valid) submit(canonical, vm, appContext) },
                enabled = valid && !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
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
    canonical: String,
    vm: CaptureViewModel,
    appContext: android.content.Context
) {
    EchoHaptics.tick(appContext)
    vm.identifyByContainer(canonical)
}

@Composable
private fun ContainerField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    accentColour: Color? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val defaultOutline = MaterialTheme.colorScheme.outline
    val defaultFocused = MaterialTheme.colorScheme.primary
    val unfocused by animateColorAsState(
        targetValue = accentColour ?: defaultOutline,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "containerFieldUnfocusedBorder"
    )
    val focused by animateColorAsState(
        targetValue = accentColour ?: defaultFocused,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "containerFieldFocusedBorder"
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 2.sp
        ),
        // Text + Caps so the system keyboard offers letters; we
        // canonicalise on the parent side anyway, but capitalisation makes
        // the visible input read as the spec expects (uppercase).
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = focused,
            unfocusedBorderColor = unfocused
        ),
        trailingIcon = trailingIcon,
        placeholder = {
            Text(
                stringResource(R.string.container_entry_placeholder),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            )
        }
    )
}

private val SUCCESS_GREEN = Color(0xFF34C759)
