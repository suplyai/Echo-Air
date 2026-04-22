package app.suply.echoair.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight wrapper around Android's BluetoothLeScanner. Returns a cold
 * Flow of [KBeacon] parsed from KSensor (0x21) frames; downstream code filters
 * to the expected roster.
 *
 * Heavy GATT interactions (connection, log download) are handled by
 * [BleConnectionManager] using kbeaconlib2.
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    fun hasPermissions(): Boolean {
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        return scan == PackageManager.PERMISSION_GRANTED && connect == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun scan(mode: Int = ScanSettings.SCAN_MODE_BALANCED): Flow<KBeacon> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("No BluetoothLeScanner available")
            close()
            return@callbackFlow
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(KBeaconIds.EDDYSTONE_SERVICE)))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val beacon = KSensorParser.parse(result) ?: return
                trySend(beacon)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> KSensorParser.parse(r)?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE scan failed: $errorCode")
                close(IllegalStateException("Scan failed code=$errorCode"))
            }
        }

        try {
            scanner.startScan(filters, settings, callback)
        } catch (t: Throwable) {
            Timber.e(t, "startScan threw")
            close(t)
            return@callbackFlow
        }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
