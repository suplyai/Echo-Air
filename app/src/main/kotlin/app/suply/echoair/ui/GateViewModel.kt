package app.suply.echoair.ui

import androidx.lifecycle.ViewModel
import app.suply.echoair.data.auth.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GateViewModel @Inject constructor(
    private val tokenStore: TokenStore
) : ViewModel() {
    fun isAuthenticated(): Boolean = !tokenStore.current().isNullOrBlank()
}
