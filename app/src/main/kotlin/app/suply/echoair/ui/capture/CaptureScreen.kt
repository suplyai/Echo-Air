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
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.QrCode2
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private enum class Mode { DOCUMENT, QR }

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
    var mode by remember { mutableStateOf(Mode.DOCUMENT) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == Mode.DOCUMENT) "Scan document" else "Scan device QR") },
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
                    Mode.DOCUMENT -> DocumentCaptureView(
                        onCaptured = { dataUrl -> vm.identify(dataUrl) }
                    )
                    Mode.QR -> QrCaptureView(
                        onScanned = { payload -> vm.lookupByQr(payload) }
                    )
                }

                // Mode toggle
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    ElevatedFilterChip(
                        selected = mode == Mode.DOCUMENT,
                        onClick = { mode = Mode.DOCUMENT },
                        label = { Text("Document") },
                        leadingIcon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) }
                    )
                    Spacer(Modifier.width(8.dp))
                    ElevatedFilterChip(
                        selected = mode == Mode.QR,
                        onClick = { mode = Mode.QR },
                        label = { Text("Device QR") },
                        leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) }
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

                state.error?.let { err ->
                    AlertDialog(
                        onDismissRequest = { vm.clear() },
                        confirmButton = { TextButton(onClick = { vm.clear() }) { Text("OK") } },
                        title = { Text("Couldn\'t identify shipment") },
                        text = { Text(err) }
                    )
                }
            }
        }
    }
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
