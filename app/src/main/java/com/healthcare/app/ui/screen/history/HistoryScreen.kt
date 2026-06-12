package com.healthcare.app.ui.screen.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.healthcare.app.data.entity.WalkingPoint
import com.healthcare.app.data.entity.WalkingSession
import com.healthcare.app.util.DateUtils
import com.healthcare.app.util.MapsApiKeyValidator
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isMapsApiKeyConfigured = MapsApiKeyValidator.isConfigured()

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.selectedSession != null) {
            // Map detail view
            TopAppBar(
                title = {
                    Text(DateUtils.formatDateTime(state.selectedSession!!.startTime))
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )

            // Session stats
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(DateUtils.formatDistance(state.selectedSession!!.totalDistanceMeters))
                        Text("距離", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("%.0f kcal".format(state.selectedSession!!.totalCalories))
                        Text("カロリー", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        val duration = if (state.selectedSession!!.endTime != null) {
                            DateUtils.formatDuration(state.selectedSession!!.endTime!! - state.selectedSession!!.startTime)
                        } else "N/A"
                        Text(duration)
                        Text("時間", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Map showing route
            val points = state.selectedSessionPoints
            val selectedPointIndex = if (points.isEmpty()) {
                -1
            } else {
                (state.timelineProgress * points.lastIndex)
                    .roundToInt()
                    .coerceIn(0, points.lastIndex)
            }
            val selectedPoint = if (selectedPointIndex >= 0) points[selectedPointIndex] else null
            val displayTimestamp = estimateTimelineTimestamp(
                selectedSession = state.selectedSession,
                points = points,
                selectedPointIndex = selectedPointIndex,
                selectedPoint = selectedPoint
            )
            val cameraPositionState = rememberCameraPositionState()

            if (points.isNotEmpty()) {
                LaunchedEffect(points) {
                    if (points.size >= 2) {
                        val boundsBuilder = LatLngBounds.builder()
                        points.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
                        val bounds = boundsBuilder.build()
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                    } else {
                        val point = points.first()
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 16f)
                        )
                    }
                }
            }

            if (selectedPoint != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "タイムライン",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = state.timelineProgress,
                            onValueChange = viewModel::onTimelineProgressChanged,
                            valueRange = 0f..1f,
                            enabled = points.size > 1
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = DateUtils.formatTime(points.first().timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = DateUtils.formatTime(points.last().timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "時刻: ${DateUtils.formatDateTime(displayTimestamp)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "時間: ${DateUtils.formatTime(displayTimestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "位置: %.5f, %.5f".format(selectedPoint.latitude, selectedPoint.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isMapsApiKeyConfigured) {
                GoogleMap(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    cameraPositionState = cameraPositionState,
                    uiSettings = MapUiSettings(zoomControlsEnabled = true)
                ) {
                    if (points.size >= 2) {
                        val path = points.map { LatLng(it.latitude, it.longitude) }
                        Polyline(
                            points = path,
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            width = 8f
                        )
                        // Start marker
                        Marker(
                            state = MarkerState(position = path.first()),
                            title = "スタート",
                            snippet = DateUtils.formatTime(points.first().timestamp)
                        )
                        // End marker
                        Marker(
                            state = MarkerState(position = path.last()),
                            title = "ゴール",
                            snippet = DateUtils.formatTime(points.last().timestamp)
                        )
                    }

                    if (selectedPoint != null) {
                        Marker(
                            state = MarkerState(position = LatLng(selectedPoint.latitude, selectedPoint.longitude)),
                            title = "選択位置",
                            snippet = DateUtils.formatDateTime(displayTimestamp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "地図を表示できません。MAPS_API_KEY が未設定、またはプレースホルダーです。",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        } else {
            // Session list
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (state.isSelectionMode) {
                    Text(
                        text = "${state.selectedSessionIds.size}件選択中",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.cancelSelectionMode() }) {
                            Text("キャンセル")
                        }
                        IconButton(
                            onClick = { viewModel.requestDeleteSelected() },
                            enabled = state.selectedSessionIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "選択した履歴を削除")
                        }
                    }
                } else {
                    Text(
                        text = "ウォーキング履歴",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    TextButton(onClick = { viewModel.enterSelectionMode() }) {
                        Text("選択")
                    }
                }
            }

            if (state.sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.DirectionsWalk,
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "まだウォーキングの記録がありません",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.sessions) { session ->
                        HistorySessionCard(
                            session = session,
                            isSelectionMode = state.isSelectionMode,
                            isSelected = session.id in state.selectedSessionIds,
                            onClick = {
                                if (state.isSelectionMode) {
                                    viewModel.toggleSessionSelection(session.id)
                                } else {
                                    viewModel.selectSession(session)
                                }
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }

        if (state.showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDeleteDialog() },
                title = { Text("履歴を削除") },
                text = { Text("選択中の${state.selectedSessionIds.size}件を削除します。この操作は取り消せません。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmDeleteSelected() }) {
                        Text("削除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDeleteDialog() }) {
                        Text("キャンセル")
                    }
                }
            )
        }

        if (state.deleteError != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDeleteError() },
                title = { Text("削除エラー") },
                text = { Text(state.deleteError ?: "") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDeleteError() }) {
                        Text("閉じる")
                    }
                }
            )
        }
    }
}

private fun estimateTimelineTimestamp(
    selectedSession: WalkingSession?,
    points: List<WalkingPoint>,
    selectedPointIndex: Int,
    selectedPoint: WalkingPoint?
): Long {
    if (selectedPoint == null || selectedPointIndex < 0) return selectedSession?.startTime ?: 0L

    if (points.size < 2 || selectedSession == null) {
        return selectedPoint.timestamp
    }

    val firstTimestamp = points.first().timestamp
    val lastTimestamp = points.last().timestamp
    val pointSpanMs = (lastTimestamp - firstTimestamp).coerceAtLeast(0L)
    val stepCount = points.lastIndex.coerceAtLeast(1)
    val avgStepMs = pointSpanMs / stepCount

    val endTime = selectedSession.endTime ?: return selectedPoint.timestamp
    val sessionSpanMs = (endTime - selectedSession.startTime).coerceAtLeast(0L)

    val seemsSyntheticTimestamp = sessionSpanMs >= 60_000L && avgStepMs < 1_000L
    if (!seemsSyntheticTimestamp) return selectedPoint.timestamp

    return selectedSession.startTime + ((sessionSpanMs * selectedPointIndex) / stepCount)
}

@Composable
private fun HistorySessionCard(
    session: WalkingSession,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelectionMode && isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column {
                Text(
                    text = DateUtils.formatDateTime(session.startTime),
                    style = MaterialTheme.typography.titleSmall
                )
                val duration = if (session.endTime != null) {
                    DateUtils.formatDuration(session.endTime - session.startTime)
                } else "進行中"
                Text(
                    text = "時間: $duration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = DateUtils.formatDistance(session.totalDistanceMeters),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "%.0f kcal".format(session.totalCalories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
