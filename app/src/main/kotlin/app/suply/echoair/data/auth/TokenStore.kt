package app.suply.echoair.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JWT persistence backed by Android Keystore. The same token is injected
 * into Retrofit calls by AuthInterceptor and into the WebView via cookie
 * injection by WebViewBridge.
 */
@Singleton
class TokenStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "echoair_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    val token: StateFlow<String?> = _token.asStateFlow()

    fun current(): String? = _token.value

    fun save(jwt: String, userEmail: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, jwt)
            .putString(KEY_EMAIL, userEmail)
            .apply()
        _token.value = jwt
    }

    fun clear() {
        prefs.edit().clear().apply()
        _token.value = null
    }

    fun email(): String? = prefs.getString(KEY_EMAIL, null)

    private companion object {
        const val KEY_TOKEN = "jwt"
        const val KEY_EMAIL = "email"
    }
}
