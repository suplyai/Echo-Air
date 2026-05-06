package app.suply.echoair.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Reactive Bluetooth-enabled state for the Collection screen's
 * pre-scan + mid-scan gates.
 *
 * Backed by a BroadcastReceiver listening on
 * [BluetoothAdapter.ACTION_STATE_CHANGED]. The initial value is read
 * synchronously from [BluetoothManager.adapter.isEnabled]; subsequent
 * toggles by the user (Quick Settings tile, system Settings, or a
 * `requestEnable` consent flow) flip the State on the next OS
 * broadcast — typically inside 100ms of the toggle.
 *
 * The receiver is registered with NOT_EXPORTED on API 33+ via
 * ContextCompat (system broadcasts don't need export) and auto-
 * unregisters on disposal.
 *
 * The same code path catches both the field-reported failure modes:
 *
 *   - Pre-collection: phone has BT off when the consignee opens the
 *     screen → state starts false → CollectionScreen blocks
 *     vm.start() and shows the BT-off dialog.
 *   - Mid-collection: BT toggled off (deliberately, by Android battery
 *     management, or accidentally) while a scan is in progress →
 *     receiver fires → state flips to false → the dialog appears
 *     reactively, no special "mid-flow alert" plumbing needed.
 */
@Composable
fun rememberBluetoothEnabled(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(currentlyEnabled(context)) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                state.value = newState == BluetoothAdapter.STATE_ON
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return state
}

/** True iff the phone's Bluetooth radio is currently on. Safe on every
 *  API level we support (minSdk 30); returns false if the adapter
 *  itself can't be resolved (BluetoothManager null on devices without
 *  BT, which our manifest's uses-feature requirement filters out, but
 *  we defend anyway). */
fun currentlyEnabled(context: Context): Boolean {
    val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return mgr?.adapter?.isEnabled == true
}

/**
 * Reactive Location-services-enabled state. BLE scanning on Android
 * silently fails when location services are off, even with all the
 * runtime permissions granted (Honor / MagicOS especially — see the
 * notes in AndroidManifest.xml). Treated symmetrically with Bluetooth:
 * blocked at scan-start and surfaced reactively if toggled mid-flow.
 *
 * [LocationManager.PROVIDERS_CHANGED_ACTION] fires on every provider
 * toggle (GPS, network); [LocationManager.isLocationEnabled] (API 28+)
 * is the canonical "is location services on at all" gate.
 */
@Composable
fun rememberLocationServicesEnabled(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(locationCurrentlyEnabled(context)) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                    state.value = locationCurrentlyEnabled(c)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return state
}

private fun locationCurrentlyEnabled(context: Context): Boolean {
    val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false
    return runCatching { mgr.isLocationEnabled }.getOrDefault(false)
}
