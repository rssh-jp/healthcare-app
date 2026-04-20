package com.healthcare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.healthcare.app.MainActivity
import com.healthcare.app.R
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.repository.WalkingRepository
import com.healthcare.app.util.CalorieCalculator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LocationTrackingService : Service() {

    @Inject lateinit var repository: WalkingRepository
    @Inject lateinit var calorieCalculator: CalorieCalculator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var currentSessionId: Long = -1
    private var totalDistance: Double = 0.0
    private var totalCalories: Double = 0.0
    private var lastLocation: Location? = null
    private var lastTimestamp: Long = 0

    companion object {
        const val CHANNEL_ID = "location_tracking_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

        private val _currentSessionId = MutableStateFlow<Long?>(null)
        val currentSessionIdFlow: StateFlow<Long?> = _currentSessionId.asStateFlow()

        private val _totalDistance = MutableStateFlow(0.0)
        val totalDistanceFlow: StateFlow<Double> = _totalDistance.asStateFlow()

        private val _totalCalories = MutableStateFlow(0.0)
        val totalCaloriesFlow: StateFlow<Double> = _totalCalories.asStateFlow()

        private val _currentSpeed = MutableStateFlow(0.0)
        val currentSpeedFlow: StateFlow<Double> = _currentSpeed.asStateFlow()

        private val _elapsedTimeMs = MutableStateFlow(0L)
        val elapsedTimeMsFlow: StateFlow<Long> = _elapsedTimeMs.asStateFlow()

        private var startTimeMs: Long = 0
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(distance: Double = 0.0): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val distanceText = if (distance >= 1000) {
            "%.2f km".format(distance / 1000.0)
        } else {
            "%.0f m".format(distance)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ウォーキング追跡中")
            .setContentText("距離: $distanceText")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    serviceScope.launch {
                        processNewLocation(location)
                    }
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startTracking() {
        serviceScope.launch {
            // End any previously active session
            repository.getActiveSession()?.let { session ->
                repository.endSession(session.id, session.totalDistanceMeters, session.totalCalories)
            }

            currentSessionId = repository.startNewSession()
            totalDistance = 0.0
            totalCalories = 0.0
            lastLocation = null
            lastTimestamp = 0
            startTimeMs = System.currentTimeMillis()

            _isTracking.value = true
            _currentSessionId.value = currentSessionId
            _totalDistance.value = 0.0
            _totalCalories.value = 0.0
            _currentSpeed.value = 0.0
            _elapsedTimeMs.value = 0

            startForeground(NOTIFICATION_ID, createNotification())

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                3000L // 3 second intervals
            ).setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(2f)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private suspend fun processNewLocation(location: Location) {
        if (currentSessionId < 0) return

        val now = System.currentTimeMillis()
        val speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0

        // Save the point
        val point = WalkingPoint(
            sessionId = currentSessionId,
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = now,
            speedMps = speedMps
        )
        repository.addPoint(point)

        // Calculate distance and calories from previous point
        lastLocation?.let { prev ->
            val results = FloatArray(1)
            Location.distanceBetween(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude,
                results
            )
            val segmentDistance = results[0].toDouble()
            val timeDelta = (now - lastTimestamp) / 1000.0

            if (segmentDistance > 0 && timeDelta > 0) {
                totalDistance += segmentDistance
                val segmentCalories = calorieCalculator.calculateCaloriesForSegment(
                    segmentDistance, timeDelta
                )
                totalCalories += segmentCalories
            }
        }

        lastLocation = location
        lastTimestamp = now

        // Update flows
        _totalDistance.value = totalDistance
        _totalCalories.value = totalCalories
        _currentSpeed.value = speedMps
        _elapsedTimeMs.value = now - startTimeMs

        // Update session stats and notification
        repository.updateSessionStats(currentSessionId, totalDistance, totalCalories)
        updateNotification()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(totalDistance))
    }

    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)

        serviceScope.launch {
            if (currentSessionId >= 0) {
                repository.endSession(currentSessionId, totalDistance, totalCalories)
            }

            _isTracking.value = false
            _currentSessionId.value = null
            _totalDistance.value = 0.0
            _totalCalories.value = 0.0
            _currentSpeed.value = 0.0
            _elapsedTimeMs.value = 0

            currentSessionId = -1
            totalDistance = 0.0
            totalCalories = 0.0
            lastLocation = null
            lastTimestamp = 0

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
