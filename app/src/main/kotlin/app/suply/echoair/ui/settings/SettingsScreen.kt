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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val ambient by vm.ambient.collectAsState()
    val email = vm.email()

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
            email?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_ambient), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_ambient_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ambient,
                            onCheckedChange = { vm.setAmbient(it) }
                        )
                    }
                }
            }

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

            TextButton(
                onClick = {
                    vm.logout()
                    onLoggedOut()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
