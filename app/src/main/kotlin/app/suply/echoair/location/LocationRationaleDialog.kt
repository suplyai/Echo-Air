package app.suply.echoair.location

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.suply.echoair.R

/**
 * One-time opt-in dialog, shown on Collection-screen entry the first
 * time the user lands on that screen after v0.4.6 is installed. Pure
 * transparency notice: we request the same ACCESS_*_LOCATION runtime
 * permission for BLE scanning on Honor / MagicOS, so the system prompt
 * is already past by the time this dialog appears — but that's a BLE
 * consent, not a "we're recording your location on the shipment
 * timeline" consent. Second gate by design; see [LocationCapture].
 *
 * Accept → opt-in flag set, future scans attach location.
 * Decline → opt-out flag set, future scans silently proceed without
 *           location. Nothing in the scan flow breaks.
 *
 * The dialog shows once per install; both outcomes mark it
 * acknowledged via [LocationCapture.setOptedIn].
 */
@Composable
fun LocationRationaleDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* modal — user must choose */ },
        title = { Text(stringResource(R.string.location_rationale_title)) },
        text = { Text(stringResource(R.string.location_rationale_body)) },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.location_rationale_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.location_rationale_decline))
            }
        }
    )
}
