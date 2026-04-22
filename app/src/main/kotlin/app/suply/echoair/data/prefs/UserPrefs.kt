package app.suply.echoair.data.prefs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("echoair_prefs", Context.MODE_PRIVATE)

    private val _ambient = MutableStateFlow(prefs.getBoolean(KEY_AMBIENT, false))
    val ambientFlow: StateFlow<Boolean> = _ambient.asStateFlow()

    fun ambientEnabled(): Boolean = _ambient.value

    fun setAmbientEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMBIENT, enabled).apply()
        _ambient.value = enabled
    }

    private companion object {
        const val KEY_AMBIENT = "ambient_enabled"
    }
}
