package com.healthcare.app.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthcare.app.data.entity.WalkingSession
import com.healthcare.app.data.repository.WalkingRepository
import com.healthcare.app.service.LocationTrackingService
import com.healthcare.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val todayDistance: Double = 0.0,
    val todayCalories: Double = 0.0,
    val todaySessionCount: Int = 0,
    val recentSessions: List<WalkingSession> = emptyList(),
    val isTracking: Boolean = false,
    val currentDistance: Double = 0.0,
    val currentCalories: Double = 0.0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: WalkingRepository
) : ViewModel() {

    private val todayStart = DateUtils.getStartOfDay(LocalDate.now())
    private val todayEnd = DateUtils.getEndOfDay(LocalDate.now())

    val uiState: StateFlow<HomeUiState> = combine(
        repository.getTotalDistanceByDateRange(todayStart, todayEnd),
        repository.getTotalCaloriesByDateRange(todayStart, todayEnd),
        repository.getSessionCountByDateRange(todayStart, todayEnd),
        repository.getSessionsByDateRange(todayStart, todayEnd),
        LocationTrackingService.isTracking
    ) { distance, calories, count, sessions, tracking ->
        HomeUiState(
            todayDistance = distance,
            todayCalories = calories,
            todaySessionCount = count,
            recentSessions = sessions.take(5),
            isTracking = tracking,
            currentDistance = LocationTrackingService.totalDistanceFlow.value,
            currentCalories = LocationTrackingService.totalCaloriesFlow.value
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeUiState()
    )
}
