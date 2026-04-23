package app.suply.echoair.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R
import app.suply.echoair.data.api.ShipmentDto
import java.util.concurrent.Executors

/** Which sub-view the Capture screen opens straight into. Each of the two
 *  home-screen CTAs picks exactly one of these; there is no in-camera mode
 *  toggle — the user already told us what they want before the camera even
 *  turned on. */
enum class CaptureMode { DOCUMENT, QR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    mode: CaptureMode,
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
                title = { Text(if (mode == CaptureMode.DOCUMENT) "Scan document" else "Scan device QR") },
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
                when (mode) {
                    CaptureMode.DOCUMENT -> DocumentCaptureView(
                        onCaptured = { dataUrl -> vm.identify(dataUrl) }
                    )
                    CaptureMode.QR -> QrCaptureView(
                        onScanned = { payload -> vm.lookupByQr(payload) }
                    )
                }

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
                        confirmButton = { TextButton(onClick = { vm.clear() }) { Text("OK") } },
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
private fun failureCopy(failure: CaptureViewModel.Failure): Pair<String, String> = when (failure) {
    CaptureViewModel.Failure.Unreachable -> Pair(
        "Can't reach Suply servers",
        "Check your internet connection, then try again."
    )
    CaptureViewModel.Failure.Timeout -> Pair(
        "Suply servers aren't responding",
        "This usually clears up after a few seconds. Try again."
    )
    is CaptureViewModel.Failure.Server -> Pair(
        "Something went wrong",
        if (failure.httpCode > 0)
            "Suply returned an error (HTTP ${failure.httpCode}). Try again in a moment, or contact your shipper if it keeps happening."
        else
            "Unexpected error. Try again in a moment."
    )
    is CaptureViewModel.Failure.NoShipmentForAwb -> Pair(
        "No matching shipment",
        "AWB ${failure.awb} was read successfully but no matching shipment was found. Check the number or contact your shipper."
    )
    CaptureViewModel.Failure.NoAwbInImage -> Pair(
        "Couldn't read the AWB",
        "Try another angle or better lighting, or scan the device QR code instead."
    )
    CaptureViewModel.Failure.DeviceNotRegistered -> Pair(
        "Device not registered",
        "This Echo Air device isn't registered in the system. Contact your shipper."
    )
    CaptureViewModel.Failure.DeviceNotAssigned -> Pair(
        "Device not on an active shipment",
        "This device is registered but isn't currently assigned to an active shipment. Contact your shipper."
    )
    CaptureViewModel.Failure.UnrecognisedQr -> Pair(
        "Unrecognised QR code",
        "Make sure you're scanning the QR label printed on an Echo Air device."
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
        Button(onClick = onRequest) { Text("Grant camera permission") }
    }
}

@Composable
private fun DocumentCaptureView(onCaptured: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val preview = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val previewUse = androidx.camera.core.Preview.Builder().build().apply {
                        setSurfaceProvider(preview.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, imageCapture)
                }, ContextCompat.getMainExecutor(ctx))
                preview
            },
            modifier = Modifier.fillMaxSize()
        )

        // Capture guide + button
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(48.dp)
                .height(260.dp)
                .background(Color.Transparent, RoundedCornerShape(12.dp))
        )

        FloatingActionButton(
            onClick = {
                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val dataUrl = ImageEncoder.toDataUrl(image)
                        image.close()
                        onCaptured(dataUrl)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        exception.printStackTrace()
                    }
                })
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        ) {
            Text("Capture")
        }
    }
}
