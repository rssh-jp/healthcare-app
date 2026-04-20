package com.healthcare.app.ui.screen.tracking

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.repository.WalkingRepository
import com.healthcare.app.service.LocationTrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TrackingUiState(
    val isTracking: Boolean = false,
    val totalDistance: Double = 0.0,
    val totalCalories: Double = 0.0,
    val currentSpeed: Double = 0.0,
    val elapsedTimeMs: Long = 0,
    val points: List<WalkingPoint> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrackingViewModel @Inject constructor(
    private val application: Application,
    private val repository: WalkingRepository
) : AndroidViewModel(application) {

    private val trackingPoints = LocationTrackingService.currentSessionIdFlow
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getPointsBySession(sessionId)
            } else {
                flowOf(emptyList())
            }
        }

    val uiState: StateFlow<TrackingUiState> = combine(
        LocationTrackingService.isTracking,
        LocationTrackingService.totalDistanceFlow,
        LocationTrackingService.totalCaloriesFlow,
        LocationTrackingService.currentSpeedFlow,
        LocationTrackingService.elapsedTimeMsFlow,
        trackingPoints
    ) { values ->
        TrackingUiState(
            isTracking = values[0] as Boolean,
            totalDistance = values[1] as Double,
            totalCalories = values[2] as Double,
            currentSpeed = values[3] as Double,
            elapsedTimeMs = values[4] as Long,
            points = @Suppress("UNCHECKED_CAST") (values[5] as List<WalkingPoint>)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        TrackingUiState()
    )

    fun startTracking() {
        val intent = Intent(application, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
        }
        application.startForegroundService(intent)
    }

    fun stopTracking() {
        val intent = Intent(application, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        application.startService(intent)
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
    }
}
