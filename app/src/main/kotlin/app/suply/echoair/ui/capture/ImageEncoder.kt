package app.suply.echoair.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageEncoder {

    /**
     * Converts a CameraX [ImageProxy] into a JPEG data URL ready for the
     * POST /api/vision/identify-shipment endpoint. Target size ~1 MB with
     * quality 80, max dimension 1600px.
     */
    fun toDataUrl(image: ImageProxy, quality: Int = 80, maxDim: Int = 1600): String {
        val bytes = image.planes[0].buffer.run {
            val arr = ByteArray(remaining())
            get(arr)
            arr
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return ""

        // Correct orientation from sensor rotation
        val rot = image.imageInfo.rotationDegrees
        if (rot != 0) {
            val matrix = Matrix().apply { postRotate(rot.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        // Cap the largest dimension
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxDim) {
            val scale = maxDim.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        bitmap.recycle()
        return "data:image/jpeg;base64,$b64"
    }
}
