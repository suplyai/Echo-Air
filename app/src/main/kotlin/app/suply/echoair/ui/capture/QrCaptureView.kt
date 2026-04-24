package app.suply.echoair.ui.capture

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.suply.echoair.ui.haptics.EchoHaptics
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 *
 * On a valid decode (classified as [QrPayloadParser.ParsedQr.Device] or
 * [QrPayloadParser.ParsedQr.Awb]) three synchronised feedback signals fire:
 *   1. Haptic tick (LongPress strength, matching the AWB-submit vocabulary).
 *   2. Viewfinder frame pulse — 200ms scale + colour tween from neutral
 *      white to success green and back.
 *   3. Centred success tick overlay — ~300ms fade/scale in, covered by the
 *      loading overlay the parent screen shows once onScanned dispatches.
 * Silence on [QrPayloadParser.ParsedQr.Unknown] — user just keeps scanning
 * and any eventual failure surfaces through the UnrecognisedQr dialog.
 *
 * No sound. See the design-direction thread in the brief — warehouse noise
 * and office-quiet environments both rule out reliable audio feedback for
 * this surface.
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
    val scope = rememberCoroutineScope()
    val appContext = context.applicationContext

    var successFlash by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { scanner.close() }
            runCatching { executor.shutdown() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val preview = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()

                    val previewUse = Preview.Builder().build().apply {
                        setSurfaceProvider(preview.surfaceProvider)
                    }

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
                                            val parsed = QrPayloadParser.parse(value)
                                            if (parsed != QrPayloadParser.ParsedQr.Unknown) {
                                                // Visual + tactile feedback fires immediately;
                                                // onScanned is delayed ~180ms so the tick has
                                                // time to register before the parent screen's
                                                // loading overlay takes over.
                                                EchoHaptics.tick(appContext)
                                                successFlash = true
                                                scope.launch {
                                                    delay(180)
                                                    onScanned(value)
                                                }
                                            } else {
                                                // Silent on unknown — UnrecognisedQr dialog
                                                // will surface the failure state.
                                                onScanned(value)
                                            }
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

        ViewfinderFrame(highlight = successFlash, modifier = Modifier.align(Alignment.Center))
        SuccessTick(visible = successFlash, modifier = Modifier.align(Alignment.Center))
    }
}

/**
 * Large rounded-corner rectangle sitting in the centre of the viewfinder.
 * On success, the border colour tweens to [SUCCESS_GREEN] and the whole
 * frame scales 1.0 → 1.05 → 1.0 over ~200ms. The two tweens run
 * concurrently so the pulse reads as a single "got it" event.
 */
@Composable
private fun ViewfinderFrame(highlight: Boolean, modifier: Modifier = Modifier) {
    val borderColour by animateColorAsState(
        targetValue = if (highlight) SUCCESS_GREEN else Color.White.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 200),
        label = "viewfinderBorder"
    )
    val scale by animateFloatAsState(
        targetValue = if (highlight) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "viewfinderScale"
    )
    Box(
        modifier = modifier
            .size(260.dp)
            .scale(scale)
            .border(3.dp, borderColour, RoundedCornerShape(24.dp))
    )
}

/**
 * Fades + scales a big green check into the centre of the viewfinder the
 * moment a valid decode lands. The parent screen's loading overlay will
 * cover it within ~180ms, so it reads as an instant confirmation flash.
 */
@Composable
private fun SuccessTick(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(80)) + scaleIn(initialScale = 0.8f, animationSpec = tween(120)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f, animationSpec = tween(120)),
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = SUCCESS_GREEN,
            modifier = Modifier.size(96.dp)
        )
    }
}

private val SUCCESS_GREEN = Color(0xFF34C759)

object QrPayloadParser {

    /** Possible interpretations of a scanned QR payload. */
    sealed interface ParsedQr {
        /** Device QR: `MAC:BC57…,SERIAL:633640;` — drives `/api/devices/lookup`. */
        data class Device(val identifier: String) : ParsedQr
        /** Label / paperwork QR that encodes an AWB — drives `/api/vision/identify-shipment`. */
        data class Awb(val awbNumber: String) : ParsedQr
        /** Payload we can't route. */
        data object Unknown : ParsedQr
    }

    fun parse(payload: String): ParsedQr {
        val clean = payload.trim().trimEnd(';')

        // Device QR form: key/value pairs joined by commas, "MAC:…,SERIAL:…;".
        if (clean.contains(":")) {
            val device = extractDeviceIdentifier(clean)
            if (device != null) return ParsedQr.Device(device)
        }

        // AWB form: 11 digits in the payload, possibly with a dash/space.
        // We accept any QR whose digits boil down to a valid IATA AWB so we
        // can accommodate both "145-12863723" printed labels and longer
        // payloads that happen to encode an AWB as a sub-token.
        app.suply.echoair.domain.Awb.canonicalise(clean)
            ?.takeIf(app.suply.echoair.domain.Awb::isValid)
            ?.let { return ParsedQr.Awb(it) }

        return ParsedQr.Unknown
    }

    /** Back-compat shim for any code path still calling this — prefer [parse]. */
    fun extractIdentifier(payload: String): String? =
        (parse(payload) as? ParsedQr.Device)?.identifier

    private fun extractDeviceIdentifier(clean: String): String? {
        val parts = clean.split(",").mapNotNull {
            val kv = it.split(":", limit = 2)
            if (kv.size == 2) kv[0].trim().uppercase() to kv[1].trim() else null
        }.toMap()
        return parts["MAC"] ?: parts["SERIAL"]
    }
}
