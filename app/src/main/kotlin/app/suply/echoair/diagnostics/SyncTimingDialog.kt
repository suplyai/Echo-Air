package app.suply.echoair.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Debug-only post-sync timing inspector. Renders the [DeviceSyncTiming]
 * captured by [SyncTimingRecorder] in a Material3 AlertDialog: section
 * headers + monospaced numeric breakdown so per-batch numbers line up
 * cleanly under each other for screenshot reading.
 *
 * "Copy" button pushes the same plain-text dump that toPlainText()
 * produces onto the system clipboard, so the field engineer can paste
 * it straight into a chat message instead of relying on a screenshot.
 *
 * This file lives under app/src/main/ but every call site is wrapped
 * in `if (BuildConfig.DEBUG)`, so release builds tree-shake the
 * reference and the dialog never inflates.
 */
@Composable
fun SyncTimingDialog(
    timing: DeviceSyncTiming,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Sync timing — device ${timing.deviceId}")
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item { HeaderRow(timing) }
                item { Section("BLE", "${timing.bleTotalMs} ms · ${"%.1f".format(timing.bleRecordsPerSecond)} rec/s") }
                item {
                    Mono(
                        listOf(
                            "connect    ${timing.connectMs} ms   mtu=${timing.negotiatedMtu ?: "?"}",
                            "info       ${timing.infoMs} ms"
                        )
                    )
                }
                items(timing.batches) { b ->
                    val rps = if (b.elapsedMs > 0) b.records * 1000.0 / b.elapsedMs else 0.0
                    Mono(
                        listOf(
                            "batch[${b.idx}]  ${b.elapsedMs} ms   ${b.records} rec   ${"%.1f".format(rps)} rec/s"
                        )
                    )
                }
                item {
                    Section(
                        "Location",
                        "${timing.locationMs ?: "—"} ms · attached=${timing.locationAttached}"
                    )
                }
                item {
                    Section("Submit", "")
                    Mono(
                        listOf(
                            "persist    ${timing.persistMs ?: "—"} ms",
                            "upload     ${timing.uploadMs ?: "—"} ms   outcome=${timing.uploadOutcome ?: "—"}"
                        )
                    )
                }
                item {
                    Section("Notes", "")
                    Text(
                        text = "kbeaconlib2 MTU target = 251 (BLE 5.x max = 517)\n" +
                            "CONNECTION_PRIORITY_HIGH not requested — BALANCED default",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, timing.toPlainText())
                onDismiss()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun HeaderRow(timing: DeviceSyncTiming) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Total: ${timing.totalMs} ms",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "${timing.recordsCollected}/${timing.totalRecords} records · MAC ${timing.mac}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Section(label: String, suffix: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = if (suffix.isBlank()) label else "$label — $suffix",
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun Mono(lines: List<String>) {
    Column {
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("Echo Air sync timing", text))
}
