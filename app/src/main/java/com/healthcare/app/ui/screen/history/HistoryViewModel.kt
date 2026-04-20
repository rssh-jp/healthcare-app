package com.healthcare.app.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession
import com.healthcare.app.data.repository.WalkingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val sessions: List<WalkingSession> = emptyList(),
    val selectedSession: WalkingSession? = null,
    val selectedSessionPoints: List<WalkingPoint> = emptyList()
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: WalkingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCompletedSessions().collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions)
            }
        }
    }

    fun selectSession(session: WalkingSession) {
        viewModelScope.launch {
            val points = repository.getPointsBySessionOnce(session.id)
            _uiState.value = _uiState.value.copy(
                selectedSession = session,
                selectedSessionPoints = points
            )
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedSession = null,
            selectedSessionPoints = emptyList()
        )
    }
}
