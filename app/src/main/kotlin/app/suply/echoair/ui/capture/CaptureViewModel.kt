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
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repo: ShipmentRepository
) : ViewModel() {

    /**
     * Distinct failure classes the capture flow can surface. Each maps to a
     * different dialog title + body in [CaptureScreen] so a consignee can
     * tell at a glance whether to retry, fix the document, contact their
     * shipper, or wait out a network blip. Previously everything landed in
     * a single "Couldn't identify shipment" dialog which sent users down
     * the wrong diagnostic path on network outages.
     */
    sealed interface Failure {
        /** TCP never connected — no network, wrong host, captive portal, etc. */
        data object Unreachable : Failure
        /** TCP connected but the server took too long to respond. */
        data object Timeout : Failure
        /** Server responded with 5xx or any unexpected non-2xx. Body carries debug
         *  detail (exception class name) so field reports from pilots are actionable. */
        data class Server(val httpCode: Int, val debugDetail: String? = null) : Failure
        /** Server responded 2xx but the JSON shape didn't match the DTO — almost
         *  always a backend/app schema drift. Surfaces the failing field so the
         *  right team can jump on it. */
        data class MalformedResponse(val detail: String) : Failure
        /** Vision AI extracted an AWB but the backend has no matching shipment. */
        data class NoShipmentForAwb(val awb: String) : Failure
        /** Vision AI couldn't read an AWB from the image. */
        data object NoAwbInImage : Failure
        /** QR payload scanned but the backend has no record of the device (404). */
        data object DeviceNotRegistered : Failure
        /** Device exists in the backend but isn't on any active shipment. */
        data object DeviceNotAssigned : Failure
        /** QR payload didn't parse as a MAC:…,SERIAL:…; tuple. */
        data object UnrecognisedQr : Failure
    }

    data class State(
        val loading: Boolean = false,
        val shipment: ShipmentDto? = null,
        val confidence: String? = null,
        val failure: Failure? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun identify(imageDataUrl: String) {
        runIdentify { repo.identifyFromImage(imageDataUrl) }
    }

    /** Manual-entry counterpart to [identify]: the user typed an AWB instead of photographing one. */
    fun identifyByAwb(awbNumber: String) {
        val clean = awbNumber.trim()
        if (clean.isBlank()) return
        runIdentify { repo.identifyFromAwb(clean) }
    }

    private fun runIdentify(block: suspend () -> app.suply.echoair.data.api.VisionResponse) {
        if (_state.value.loading) return
        _state.value = State(loading = true)
        viewModelScope.launch {
            try {
                val resp = block()
                val shipment = resp.shipment
                val awb = resp.awbNumber
                _state.value = when {
                    shipment != null -> State(shipment = shipment, confidence = resp.confidence)
                    awb != null -> State(failure = Failure.NoShipmentForAwb(awb))
                    else -> State(failure = Failure.NoAwbInImage)
                }
            } catch (t: Throwable) {
                Timber.w(t, "identify failed")
                _state.value = State(failure = classify(t))
            }
        }
    }

    fun lookupByQr(payload: String) {
        if (_state.value.loading) return
        val identifier = QrPayloadParser.extractIdentifier(payload)
        if (identifier.isNullOrBlank()) {
            _state.value = State(failure = Failure.UnrecognisedQr)
            return
        }
        runDeviceLookup(identifier)
    }

    /** Manual-entry counterpart to [lookupByQr]: user typed a device ID / MAC / serial. */
    fun lookupByIdentifier(identifier: String) {
        val clean = identifier.trim()
        if (clean.isBlank()) return
        runDeviceLookup(clean)
    }

    private fun runDeviceLookup(identifier: String) {
        if (_state.value.loading) return
        _state.value = State(loading = true)
        viewModelScope.launch {
            try {
                val shipment = repo.lookupDevice(identifier)
                _state.value =
                    if (shipment != null) State(shipment = shipment, confidence = "high")
                    else State(failure = Failure.DeviceNotAssigned)
            } catch (t: HttpException) {
                if (t.code() == 404) _state.value = State(failure = Failure.DeviceNotRegistered)
                else _state.value = State(failure = classify(t))
            } catch (t: Throwable) {
                Timber.w(t, "lookup failed")
                _state.value = State(failure = classify(t))
            }
        }
    }

    fun clear() {
        _state.value = State()
    }

    private fun classify(t: Throwable): Failure = when (t) {
        is UnknownHostException, is ConnectException -> Failure.Unreachable
        is SocketTimeoutException -> Failure.Timeout
        is HttpException -> Failure.Server(t.code())
        is SerializationException -> Failure.MalformedResponse(t.message ?: t::class.java.simpleName)
        is IOException -> Failure.Unreachable   // generic network failure
        else -> Failure.Server(httpCode = 0, debugDetail = "${t::class.java.simpleName}: ${t.message}")
    }
}
