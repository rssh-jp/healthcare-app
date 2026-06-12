package com.healthcare.app.ui.screen.tracking

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.healthcare.app.util.DateUtils
import com.healthcare.app.util.MapsApiKeyValidator

@Composable
fun TrackingScreen(viewModel: TrackingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fusedLocationClient = remember(context) {
        LocationServices.getFusedLocationProviderClient(context)
    }
    var hasPermission by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val isMapsApiKeyConfigured = MapsApiKeyValidator.isConfigured()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasPermission) {
            viewModel.startTracking()
        }
    }

    val cameraPositionState = rememberCameraPositionState()
    var cameraInitialized by remember { mutableStateOf(false) }

    val points = state.points

    // 追跡停止時に初期化フラグをリセット
    LaunchedEffect(state.isTracking) {
        if (!state.isTracking) {
            cameraInitialized = false
        }
    }

    // 最新地点へカメラを追従（ユーザーのズームレベルを維持）
    LaunchedEffect(points.lastOrNull()) {
        if (points.isNotEmpty()) {
            val lastPoint = points.last()
            val target = LatLng(lastPoint.latitude, lastPoint.longitude)
            if (!cameraInitialized) {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target, 16f))
                cameraInitialized = true
            } else {
                cameraPositionState.animate(CameraUpdateFactory.newLatLng(target))
            }
        }
    }

    // 初期表示は現在地を優先し、取れない場合のみ前回最終地点へフォールバックする
    LaunchedEffect(
        hasPermission,
        points.isEmpty(),
        cameraInitialized,
        state.latestCompletedSessionLastPoint
    ) {
        if (hasPermission && points.isEmpty() && !cameraInitialized) {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    val target = when {
                        location != null -> LatLng(location.latitude, location.longitude)
                        else -> state.latestCompletedSessionLastPoint?.let {
                            LatLng(it.latitude, it.longitude)
                        }
                    }

                    if (target != null) {
                        cameraPositionState.move(
                            CameraUpdateFactory.newLatLngZoom(target, 16f)
                        )
                        cameraInitialized = true
                    }
                }
                .addOnFailureListener {
                    state.latestCompletedSessionLastPoint?.let { fallback ->
                        cameraPositionState.move(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(fallback.latitude, fallback.longitude),
                                16f
                            )
                        )
                        cameraInitialized = true
                    }
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Map
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isMapsApiKeyConfigured) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = hasPermission),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true)
                ) {
                    if (points.size >= 2) {
                        val path = points.map { LatLng(it.latitude, it.longitude) }
                        Polyline(
                            points = path,
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            width = 8f
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "地図を表示できません",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "MAPS_API_KEY が未設定、またはプレースホルダーです。local.properties を確認してください。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (!hasPermission && !state.isTracking && isMapsApiKeyConfigured) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "位置情報の許可が必要です",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val permissions = mutableListOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                            if (viewModel.needsNotificationPermission()) {
                                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionLauncher.launch(permissions.toTypedArray())
                        }) {
                            Text("許可する")
                        }
                    }
                }
            }
        }

        // Stats bar
        if (state.isTracking) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TrackingStat(
                        icon = Icons.Default.Route,
                        value = DateUtils.formatDistance(state.totalDistance),
                        label = "距離"
                    )
                    TrackingStat(
                        icon = Icons.Default.Timer,
                        value = DateUtils.formatDuration(state.elapsedTimeMs),
                        label = "時間"
                    )
                    TrackingStat(
                        icon = Icons.Default.Speed,
                        value = "%.1f km/h".format(state.currentSpeed * 3.6),
                        label = "速度"
                    )
                    TrackingStat(
                        icon = Icons.Default.LocalFireDepartment,
                        value = "%.0f kcal".format(state.totalCalories),
                        label = "カロリー"
                    )
                }
            }
        }

        // Start/Stop button
        Button(
            onClick = {
                if (state.isTracking) {
                    viewModel.stopTracking()
                } else if (hasPermission) {
                    viewModel.startTracking()
                } else {
                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (viewModel.needsNotificationPermission()) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
            colors = if (state.isTracking) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Icon(
                imageVector = if (state.isTracking) Icons.Default.Stop else Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = if (state.isTracking) "ウォーキングを停止" else "ウォーキングを開始",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun TrackingStat(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}
