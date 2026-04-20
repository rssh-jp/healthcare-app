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
    val selectedSessionPoints: List<WalkingPoint> = emptyList(),
    val isSelectionMode: Boolean = false,
    val selectedSessionIds: Set<Long> = emptySet(),
    val showDeleteConfirmDialog: Boolean = false
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
                val current = _uiState.value
                val availableIds = sessions.map { it.id }.toSet()
                val filteredSelectedIds = current.selectedSessionIds.filter { it in availableIds }.toSet()
                val selectedSession = current.selectedSession?.takeIf { it.id in availableIds }

                _uiState.value = current.copy(
                    sessions = sessions,
                    selectedSession = selectedSession,
                    selectedSessionPoints = if (selectedSession == null) emptyList() else current.selectedSessionPoints,
                    selectedSessionIds = filteredSelectedIds,
                    isSelectionMode = current.isSelectionMode && sessions.isNotEmpty(),
                    showDeleteConfirmDialog = current.showDeleteConfirmDialog && filteredSelectedIds.isNotEmpty()
                )
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

    fun enterSelectionMode() {
        if (_uiState.value.sessions.isEmpty()) return
        _uiState.value = _uiState.value.copy(isSelectionMode = true)
    }

    fun cancelSelectionMode() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedSessionIds = emptySet(),
            showDeleteConfirmDialog = false
        )
    }

    fun toggleSessionSelection(sessionId: Long) {
        if (!_uiState.value.isSelectionMode) return

        val updated = _uiState.value.selectedSessionIds.toMutableSet().apply {
            if (contains(sessionId)) remove(sessionId) else add(sessionId)
        }

        _uiState.value = _uiState.value.copy(selectedSessionIds = updated)
    }

    fun requestDeleteSelected() {
        if (_uiState.value.selectedSessionIds.isEmpty()) return
        _uiState.value = _uiState.value.copy(showDeleteConfirmDialog = true)
    }

    fun dismissDeleteDialog() {
        _uiState.value = _uiState.value.copy(showDeleteConfirmDialog = false)
    }

    fun confirmDeleteSelected() {
        val idsToDelete = _uiState.value.selectedSessionIds
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch {
            repository.deleteSessionsByIds(idsToDelete)
            _uiState.value = _uiState.value.copy(
                isSelectionMode = false,
                selectedSessionIds = emptySet(),
                showDeleteConfirmDialog = false
            )
        }
    }
}
