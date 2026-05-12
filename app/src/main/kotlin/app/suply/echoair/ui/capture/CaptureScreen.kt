package app.suply.echoair.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R

/**
 * QR-code-only capture screen. Earlier iterations also offered a
 * vision-AI document scan path (CaptureMode.DOCUMENT, removed in
 * v0.6.1) — we ship QR-only because the manual AWB entry flow plus
 * QR scan covers every legitimate consignee path, and the vision
 * path was never used in production.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onCancel: () -> Unit,
    onShipmentReady: (shipmentId: String) -> Unit,
    vm: CaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.capture_title_qr)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = null) }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!granted) {
                PermissionGate(
                    onRequest = { permLauncher.launch(Manifest.permission.CAMERA) }
                )
            } else {
                QrCaptureView(
                    onScanned = { payload -> vm.onQrPayload(payload) }
                )

                if (state.loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }

                state.shipment?.let { dto ->
                    ConfirmShipmentDialog(
                        shipment = dto,
                        confidence = state.confidence,
                        onConfirm = {
                            onShipmentReady(dto.id)
                            vm.clear()
                        },
                        onCancel = { vm.clear() }
                    )
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
            }
        }
    }
}

/**
 * Maps [CaptureViewModel.Failure] to dialog copy. Each failure class gets a
 * distinct title + body so a consignee can tell whether to retry, fix the
 * document, contact their shipper, or wait out a network blip — rather than
 * every failure landing in a single "Couldn't identify shipment" dialog.
 */
@Composable
internal fun failureCopy(failure: CaptureViewModel.Failure): Pair<String, String> = when (failure) {
    CaptureViewModel.Failure.Unreachable -> Pair(
        stringResource(R.string.failure_unreachable_title),
        stringResource(R.string.failure_unreachable_body)
    )
    CaptureViewModel.Failure.Timeout -> Pair(
        stringResource(R.string.failure_timeout_title),
        stringResource(R.string.failure_timeout_body)
    )
    is CaptureViewModel.Failure.Server -> {
        val primary = if (failure.httpCode > 0)
            stringResource(R.string.failure_server_body_with_code, failure.httpCode)
        else
            stringResource(R.string.failure_server_body_unknown)
        val body = if (app.suply.echoair.BuildConfig.DEBUG && !failure.debugDetail.isNullOrBlank())
            primary + stringResource(R.string.failure_debug_detail_suffix, failure.debugDetail)
        else primary
        Pair(stringResource(R.string.failure_server_title), body)
    }
    is CaptureViewModel.Failure.MalformedResponse -> {
        val primary = stringResource(R.string.failure_malformed_body)
        val body = if (app.suply.echoair.BuildConfig.DEBUG)
            primary + stringResource(R.string.failure_debug_detail_suffix, failure.detail)
        else primary
        Pair(stringResource(R.string.failure_malformed_title), body)
    }
    is CaptureViewModel.Failure.NoShipmentForAwb -> Pair(
        stringResource(R.string.failure_no_shipment_for_awb_title),
        stringResource(R.string.failure_no_shipment_for_awb_body, failure.awb)
    )
    is CaptureViewModel.Failure.NoShipmentForContainer -> Pair(
        stringResource(R.string.failure_no_shipment_for_container_title),
        stringResource(R.string.failure_no_shipment_for_container_body, failure.containerNumber)
    )
    CaptureViewModel.Failure.NoAwbInImage -> Pair(
        stringResource(R.string.failure_no_awb_in_image_title),
        stringResource(R.string.failure_no_awb_in_image_body)
    )
    CaptureViewModel.Failure.DeviceNotRegistered -> Pair(
        stringResource(R.string.failure_device_not_registered_title),
        stringResource(R.string.failure_device_not_registered_body)
    )
    CaptureViewModel.Failure.DeviceNotAssigned -> Pair(
        stringResource(R.string.failure_device_not_assigned_title),
        stringResource(R.string.failure_device_not_assigned_body)
    )
    CaptureViewModel.Failure.UnrecognisedQr -> Pair(
        stringResource(R.string.failure_unrecognised_qr_title),
        stringResource(R.string.failure_unrecognised_qr_body)
    )
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.permission_camera_rationale))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.capture_permission_grant)) }
    }
}
