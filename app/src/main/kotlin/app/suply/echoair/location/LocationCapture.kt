package app.suply.echoair.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.suply.echoair.data.api.LocationDto
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Point-in-time location capture for /api/echo-scan payloads.
 *
 * Privacy-by-design constraints (enforced by this class; asserted here
 * so the constraints live in code rather than only in documentation):
 *
 *   1. Location is captured ONLY via [captureOnce], and [captureOnce]
 *      is only ever called at the moment of a successful GATT
 *      collection — never continuously, never on app launch, never in
 *      the background. If you see a caller outside
 *      CollectionOrchestrator.triggerDownload, that's a regression.
 *
 *   2. Location is transmitted ONLY as part of the EchoScanRequest.
 *      It is not logged to disk, not cached, not attached to any
 *      other API call.
 *
 *   3. The OS permission alone does not grant capture. Users
 *      explicitly opt in via [LocationRationaleDialog] → [setOptedIn].
 *      We already request ACCESS_FINE_LOCATION at Collection-screen
 *      entry for BLE scanning on Honor / MagicOS, so the system prompt
 *      is already past for most users — but that's a BLE consent, not
 *      a timeline-capture consent. The opt-in flag is the second gate.
 *
 *   4. Any failure (declined opt-in, revoked permission, Play Services
 *      absent, GPS off, timeout) returns null silently. The scan
 *      proceeds as before, just without a location on the payload.
 *      This is the SINGLE failure mode for the whole subsystem — there
 *      is no error surface visible to the user.
 */
@Singleton
class LocationCapture @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lazily instantiated — constructing it on systems without Google
     *  Play Services would still succeed (it's client-side), but
     *  getCurrentLocation would fail. We don't probe availability; we
     *  just let the call fail and fall through to null. */
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    /** True once the rationale dialog has been shown at least once
     *  (accepted OR declined). Used by the UI to decide whether to
     *  present the dialog. */
    fun isAcknowledged(): Boolean = prefs.getString(KEY_CONSENT, null) != null

    /** True only when the user has explicitly accepted the rationale. */
    fun isOptedIn(): Boolean = prefs.getString(KEY_CONSENT, null) == VALUE_ACCEPTED

    fun setOptedIn(optedIn: Boolean) {
        prefs.edit()
            .putString(KEY_CONSENT, if (optedIn) VALUE_ACCEPTED else VALUE_DECLINED)
            .apply()
        Timber.i("Location opt-in set to %s", if (optedIn) "accepted" else "declined")
    }

    /**
     * Returns a fresh location fix as a ready-to-serialize [LocationDto],
     * or null on any failure (not opted in, permission denied, Play
     * Services absent, provider disabled, timeout). Never throws.
     *
     * Suspend function — safe to call from a coroutine in parallel with
     * the GATT download; the orchestrator awaits() it right before the
     * /api/echo-scan POST.
     */
    suspend fun captureOnce(timeoutMs: Long = 8_000L): LocationDto? {
        if (!isOptedIn()) {
            Timber.d("captureOnce: user not opted in, skipping")
            return null
        }
        if (!hasRuntimePermission()) {
            Timber.d("captureOnce: runtime permission not granted, skipping")
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { awaitCurrentLocation() }.also { dto ->
                if (dto != null) {
                    Timber.i(
                        "location captured: lat=%.5f lng=%.5f acc=%.1fm at %s",
                        dto.latitude, dto.longitude, dto.accuracyM, dto.capturedAt
                    )
                } else {
                    Timber.d("captureOnce: no fix within %dms", timeoutMs)
                }
            }
        } catch (t: Throwable) {
            // FusedLocationProviderClient is known to throw SecurityException
            // on some OEMs even when permission reads as granted, and to
            // throw NPE when Play Services is mid-update. We swallow all of
            // it — location is optional, never break the scan.
            Timber.w(t, "captureOnce threw; falling through to null")
            null
        }
    }

    private suspend fun awaitCurrentLocation(): LocationDto? =
        suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    val dto = loc?.let {
                        LocationDto(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            accuracyM = it.accuracy,
                            capturedAt = Instant.now().toString()
                        )
                    }
                    if (!cont.isCompleted) cont.resume(dto)
                }
                .addOnFailureListener { t ->
                    Timber.w(t, "getCurrentLocation failed")
                    if (!cont.isCompleted) cont.resume(null)
                }
                .addOnCanceledListener {
                    if (!cont.isCompleted) cont.resume(null)
                }
        }

    private fun hasRuntimePermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Either grade is sufficient — coarse still gives us ~100m, plenty
        // for a shipment checkpoint. The BLE flow requires FINE so in
        // practice this will always be true once the user has reached the
        // Collection screen, but check both for safety.
        return fine || coarse
    }

    private companion object {
        const val PREFS = "echoair_location"
        const val KEY_CONSENT = "consent"
        const val VALUE_ACCEPTED = "accepted"
        const val VALUE_DECLINED = "declined"
    }
}
