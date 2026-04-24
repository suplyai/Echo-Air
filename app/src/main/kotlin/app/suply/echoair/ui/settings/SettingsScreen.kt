package app.suply.echoair.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.suply.echoair.BuildConfig
import app.suply.echoair.R

/**
 * Stateless app — Settings has no account/logout because there is no
 * account. Surface is intentionally minimal: battery-optimisation prompt
 * (so foreground BLE work doesn't get killed mid-collection) and, in debug
 * builds, the kbeaconlib2 spike harness.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val pm = context.getSystemService(PowerManager::class.java)
                    val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                    if (!ignoring) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_battery_optimisation)) }

            if (BuildConfig.DEBUG) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent().apply {
                            setClassName(context, "app.suply.echoair.spike.SpikeActivity")
                        }
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_debug_run_spike)) }
            }
        }
    }
}
