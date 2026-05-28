package app.suply.echoair.update

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.suply.echoair.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Hosts the cold-launch update check + the dismissible "Update available"
 * prompt. Sits at MainActivity scope so the dialog renders over any
 * destination. Fires the network call exactly once per ViewModel lifetime
 * (i.e. once per Activity creation — surviving configuration changes via
 * the standard ViewModelStore). Result is observed by [UpdateAvailableGate].
 */
@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    private val checker: UpdateChecker
) : ViewModel() {

    private val _result = MutableStateFlow<UpdateResult?>(null)
    val result: StateFlow<UpdateResult?> = _result.asStateFlow()

    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    init {
        viewModelScope.launch {
            _result.value = checker.check()
        }
    }

    fun dismiss() {
        _dismissed.value = true
    }
}

/**
 * Renders the "Update available" dialog when the cold-launch check
 * surfaces a newer remote versionCode. The dialog is:
 *   - non-blocking (the app is fully usable underneath while it's up),
 *   - dismissible (tap-outside, Later button, or hardware back),
 *   - localised (en / es / zh / ja),
 *   - safe to no-op (Available with a malformed downloadUrl just
 *     silently fails the Intent launch; the user can dismiss).
 *
 * Place once near the root of the Compose tree. Returns immediately
 * with no UI in the common (no-update) path, so it's free to drop in
 * everywhere without affecting layout.
 */
@Composable
fun UpdateAvailableGate() {
    val vm: UpdateCheckViewModel = hiltViewModel()
    val result by vm.result.collectAsState()
    val dismissed by vm.dismissed.collectAsState()
    val context = LocalContext.current

    val available = (result as? UpdateResult.Available)?.takeIf { !dismissed } ?: return
    val latest = available.latest

    AlertDialog(
        onDismissRequest = { vm.dismiss() },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            val notes = latest.releaseNotes?.takeIf { it.isNotBlank() }
            Text(
                if (notes != null) {
                    stringResource(R.string.update_dialog_body_with_notes, latest.versionName, notes)
                } else {
                    stringResource(R.string.update_dialog_body, latest.versionName)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(latest.downloadUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.onFailure { Timber.w(it, "Update download intent failed for %s", latest.downloadUrl) }
                vm.dismiss()
            }) {
                Text(stringResource(R.string.update_dialog_download))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismiss() }) {
                Text(stringResource(R.string.update_dialog_later))
            }
        }
    )
}
