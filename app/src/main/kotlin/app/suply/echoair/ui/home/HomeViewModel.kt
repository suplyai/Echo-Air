package app.suply.echoair.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.suply.echoair.data.ShipmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: ShipmentRepository
) : ViewModel() {

    val activeShipments = repo.activeShipments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { repo.refreshActiveRoster() }
                .onFailure { Timber.w(it, "refreshActiveRoster") }
        }
    }
}
