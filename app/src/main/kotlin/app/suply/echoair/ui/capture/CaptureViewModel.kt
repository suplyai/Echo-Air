package app.suply.echoair.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.suply.echoair.data.ShipmentRepository
import app.suply.echoair.data.api.ShipmentDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repo: ShipmentRepository
) : ViewModel() {

    data class State(
        val loading: Boolean = false,
        val shipment: ShipmentDto? = null,
        val confidence: String? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun identify(imageDataUrl: String) {
        if (_state.value.loading) return
        _state.value = State(loading = true)
        viewModelScope.launch {
            try {
                val resp = repo.identifyFromImage(imageDataUrl)
                val shipment = resp.shipment
                when {
                    shipment != null -> _state.value = State(shipment = shipment, confidence = resp.confidence)
                    resp.awbNumber != null -> _state.value = State(
                        error = "AWB ${resp.awbNumber} extracted but no matching shipment in your organisation. Check the number or contact your team."
                    )
                    else -> _state.value = State(error = "Couldn\'t read an AWB from this image. Try another angle or enter manually.")
                }
            } catch (t: Throwable) {
                Timber.w(t, "identify failed")
                _state.value = State(error = t.message ?: "Vision AI failed. Check your connection.")
            }
        }
    }

    fun lookupByQr(payload: String) {
        if (_state.value.loading) return
        val identifier = QrPayloadParser.extractIdentifier(payload)
        if (identifier.isNullOrBlank()) {
            _state.value = State(error = "Unrecognised QR code.")
            return
        }
        _state.value = State(loading = true)
        viewModelScope.launch {
            try {
                val shipment = repo.lookupDevice(identifier)
                if (shipment != null) {
                    _state.value = State(shipment = shipment, confidence = "high")
                } else {
                    _state.value = State(error = "This device isn\'t assigned to an active shipment.")
                }
            } catch (t: Throwable) {
                Timber.w(t, "lookup failed")
                _state.value = State(error = t.message ?: "Device lookup failed.")
            }
        }
    }

    fun clear() {
        _state.value = State()
    }
}
