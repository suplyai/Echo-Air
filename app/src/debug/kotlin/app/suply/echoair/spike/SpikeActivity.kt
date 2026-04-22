package app.suply.echoair.spike

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Debug-only entry point. Enter a MAC (e.g. "BC:57:29:1C:D6:A6"), tap Run,
 * and eyeball the output. Also writes to Logcat with tag `EchoAirSpike`.
 *
 * Not registered in the shipping app manifest — only in src/debug/AndroidManifest.xml.
 */
class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { SpikeScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpikeScreen() {
    var mac by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("0000000000000000") }
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val perms = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Always request FINE_LOCATION — OEMs like Honor silently require it
            // for BLE scanning on 12+ despite the neverForLocation declaration.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) { permLauncher.launch(perms) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("kbeaconlib2 spike") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = mac, onValueChange = { mac = it },
                label = { Text("Device MAC (BC:57:29:1C:D6:A6)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    running = true
                    log = emptyList()
                    scope.launch {
                        val report = KBeaconLibSpike.run(context, mac.trim(), password)
                        log = report.log
                        running = false
                    }
                },
                enabled = !running && mac.isNotBlank()
            ) {
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Run spike")
            }

            Divider()
            Text("Log output", style = MaterialTheme.typography.titleMedium)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                log.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
