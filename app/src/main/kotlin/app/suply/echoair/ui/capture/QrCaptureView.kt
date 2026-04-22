package app.suply.echoair.ui.capture

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Uses ML Kit Barcode Scanning to decode the physical Echo Air device QR code.
 * QR payload format: `MAC:BC57291CD6A6,SERIAL:633640;`
 */
@Composable
fun QrCaptureView(onScanned: (payload: String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                .build()
        )
    }
    var scanned = remember { Any() }

    AndroidView(
        factory = { ctx ->
            val preview = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val previewUse = Preview.Builder().build().apply {
                    setSurfaceProvider(preview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null) { proxy.close(); return@setAnalyzer }
                            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    val value = codes.firstNotNullOfOrNull { it.rawValue }
                                    if (value != null && synchronized(scanned) { true }) {
                                        onScanned(value)
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis
                )
            }, ContextCompat.getMainExecutor(ctx))
            preview
        },
        modifier = Modifier.fillMaxSize()
    )
}

object QrPayloadParser {
    /** Returns the MAC or serial from a payload like `MAC:AA..,SERIAL:633640;`. */
    fun extractIdentifier(payload: String): String? {
        val clean = payload.trim().trimEnd(';')
        val parts = clean.split(",").mapNotNull {
            val kv = it.split(":", limit = 2)
            if (kv.size == 2) kv[0].trim().uppercase() to kv[1].trim() else null
        }.toMap()
        return parts["MAC"] ?: parts["SERIAL"]
    }
}
