package app.suply.echoair.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.suply.echoair.data.api.LoginRequest
import app.suply.echoair.data.api.SuplyApi
import app.suply.echoair.data.auth.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val api: SuplyApi,
    private val tokenStore: TokenStore
) : ViewModel() {

    data class State(
        val loading: Boolean = false,
        val error: String? = null,
        val success: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun submit(email: String, password: String) {
        if (_state.value.loading) return
        _state.value = State(loading = true)
        viewModelScope.launch {
            try {
                val resp = api.login(LoginRequest(email, password))
                tokenStore.save(resp.token, resp.user?.email ?: email)
                _state.value = State(success = true)
            } catch (t: Throwable) {
                Timber.w(t, "login failed")
                _state.value = State(error = t.message ?: "Sign-in failed")
            }
        }
    }
}
