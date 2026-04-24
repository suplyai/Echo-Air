package app.suply.echoair.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Platform-Vibrator haptic helper.
 *
 * Compose's [androidx.compose.ui.hapticfeedback.HapticFeedback] is a thin
 * wrapper around `View.performHapticFeedback(LONG_PRESS)`, which respects
 * both the view-level and the global-system "Haptic feedback" toggle.
 * Several OEM skins (Honor / MagicOS confirmed) silently suppress those
 * constants in contexts where we expect a firm pulse — the consignee sees
 * the visual tick but gets no physical vibration, which breaks the
 * "confirmed scan" signal on exactly the hardware we need to support.
 *
 * Going direct to [Vibrator] with [VibrationEffect.EFFECT_CLICK] gets us
 * a standardised, reliably-perceptible tick on API 29+ and a 40ms one-shot
 * as a fallback on older devices. Requires `android.permission.VIBRATE`
 * (declared in the manifest).
 *
 * This does not fire when Do Not Disturb is set to suppress vibrations —
 * correct behaviour; we respect user intent.
 */
object EchoHaptics {

    /** Firm "got it" tick — use at confirmed-scan / submit-success moments. */
    fun tick(context: Context) {
        val vibrator = platformVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun platformVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
