package app.suply.echoair.ui.capture

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes the Echo Air device QR code (payload `MAC:BC57…,SERIAL:633640;`) via
 * ML Kit Barcode Scanning. Tuned for fast, close-range reads on small labels
 * common in logistics: 720p analysis stream, continuous back-camera scan,
 * QR-only format filter, and a one-shot debounce so the first valid code
 * advances immediately and subsequent frames on the same code don't fire
 * repeated onScanned callbacks.
 */
@Composable
fun QrCaptureView(onScanned: (payload: String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val consumed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { scanner.close() }
            runCatching { executor.shutdown() }
        }
    }

    AndroidView(
        factory = { ctx ->
            val preview = PreviewView(ctx).apply {
                // Continuous autofocus is the CameraX default on a PreviewView
                // bound to the lifecycle; no extra setup needed. Keep in mind
                // when diagnosing focus issues on specific devices.
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()

                val previewUse = Preview.Builder().build().apply {
                    setSurfaceProvider(preview.surfaceProvider)
                }

                // 720p is a sweet spot for QR: high enough for small labels
                // on shipping docs, low enough that ML Kit returns results in
                // well under 100 ms per frame on mid-range hardware.
                val analysisResolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisResolution)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(executor) { proxy ->
                            if (consumed.get()) { proxy.close(); return@setAnalyzer }
                            val media = proxy.image
                            if (media == null) { proxy.close(); return@setAnalyzer }
                            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    val value = codes.firstNotNullOfOrNull { it.rawValue }
                                    if (value != null && consumed.compareAndSet(false, true)) {
                                        Timber.d("QR detected: %s", value)
                                        onScanned(value)
                                    }
                                }
                                .addOnFailureListener { Timber.w(it, "QR process failed") }
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
