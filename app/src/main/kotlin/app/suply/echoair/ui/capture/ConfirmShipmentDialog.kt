package app.suply.echoair.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.suply.echoair.R
import app.suply.echoair.data.api.ShipmentDto

@Composable
fun ConfirmShipmentDialog(
    shipment: ShipmentDto,
    confidence: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val title = when (confidence) {
        "low" -> "Is this the right shipment?"
        "medium" -> "Is this right?"
        else -> stringResource(R.string.capture_confirm_title)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    shipment.airwayBillNumber,
                    style = MaterialTheme.typography.titleMedium
                )
                val route = listOfNotNull(shipment.airOriginIata, shipment.airDestIata).joinToString(" → ")
                if (route.isNotBlank()) Text(route)
                shipment.cargoProfile?.let { cp ->
                    val tempRange = if (cp.minTemp != null && cp.maxTemp != null)
                        "${cp.minTemp}–${cp.maxTemp}°C" else null
                    val line = listOfNotNull(cp.name, tempRange).joinToString(", ")
                    if (line.isNotBlank()) {
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val devicesCount = shipment.devices.size
                if (devicesCount > 0) {
                    Text(
                        "$devicesCount Echo Air ${if (devicesCount == 1) "device" else "devices"} expected",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.capture_start_collecting))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.capture_cancel)) }
        }
    )
}
