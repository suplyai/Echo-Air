package app.suply.echoair.update

import app.suply.echoair.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot update check used by the side-loaded distribution channel
 * (primarily mainland China, where Play Store isn't available and the
 * APK is downloaded directly from suply.ai). Side-loaded installs don't
 * get Play Store's automatic update mechanism, so the app polls a
 * lightweight JSON endpoint at cold launch and surfaces a dismissible
 * "Update available" prompt when [LatestVersionDto.versionCode] is
 * strictly greater than [BuildConfig.VERSION_CODE].
 *
 * Hard rules:
 *   - Fails closed. Any network, parse, HTTP-status, or comparison
 *     failure returns [UpdateResult.Failed]; the UI treats Failed
 *     identically to NoUpdate (i.e. shows nothing). The user must
 *     never see an error for an update check — only a positive
 *     "there's a new version" prompt.
 *   - Strictly remote > local: equal versions show nothing, lower
 *     versions show nothing. Protects against a misconfigured remote.
 *   - Non-blocking and best-effort. Tight 5-second call ceiling so a
 *     slow endpoint never blocks the launch experience.
 */

@Serializable
data class LatestVersionDto(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("versionName") val versionName: String,
    @SerialName("downloadUrl") val downloadUrl: String,
    /** Optional one-line "what's new" rendered under the body copy. */
    @SerialName("releaseNotes") val releaseNotes: String? = null
)

sealed interface UpdateResult {
    data object NoUpdate : UpdateResult
    data class Available(val latest: LatestVersionDto) : UpdateResult
    data object Failed : UpdateResult
}

@Singleton
class UpdateChecker @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(ENDPOINT)
                .get()
                .build()
            // Tighten the shared client's timeouts for this single call —
            // the global config is tuned for vision (60s read), which is way
            // too patient for an opportunistic update poll.
            val callClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            callClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.i("Update check: HTTP %d, treating as no update", response.code)
                    return@withContext UpdateResult.Failed
                }
                val body = response.body?.string()
                    ?: return@withContext UpdateResult.Failed
                val dto = json.decodeFromString(LatestVersionDto.serializer(), body)
                if (dto.versionCode > BuildConfig.VERSION_CODE) {
                    Timber.i(
                        "Update available: %d (%s) — running %d (%s)",
                        dto.versionCode, dto.versionName,
                        BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME
                    )
                    UpdateResult.Available(dto)
                } else {
                    UpdateResult.NoUpdate
                }
            }
        }.getOrElse { t ->
            Timber.i(t, "Update check failed silently")
            UpdateResult.Failed
        }
    }

    companion object {
        const val ENDPOINT = "https://api.suply.ai/echo/android-latest-version"
    }
}
