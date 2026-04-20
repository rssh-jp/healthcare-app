package com.healthcare.app.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthcare.app.data.dao.DailyAggregation
import com.healthcare.app.data.entity.WalkingSession
import com.healthcare.app.data.repository.WalkingRepository
import com.healthcare.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

enum class StatsPeriod(val label: String) {
    DAY("日"),
    WEEK("週"),
    MONTH("月"),
    CUSTOM("カスタム")
}

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.DAY,
    val currentDate: LocalDate = LocalDate.now(),
    val customStartDate: LocalDate = LocalDate.now().minusDays(7),
    val customEndDate: LocalDate = LocalDate.now(),
    val totalDistance: Double = 0.0,
    val totalCalories: Double = 0.0,
    val sessionCount: Int = 0,
    val dailyData: List<DailyAggregation> = emptyList(),
    val sessions: List<WalkingSession> = emptyList(),
    val periodLabel: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: WalkingRepository
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.DAY)
    private val _currentDate = MutableStateFlow(LocalDate.now())
    private val _customStartDate = MutableStateFlow(LocalDate.now().minusDays(7))
    private val _customEndDate = MutableStateFlow(LocalDate.now())

    private val dateRange = combine(_period, _currentDate, _customStartDate, _customEndDate) { period, date, customStart, customEnd ->
        when (period) {
            StatsPeriod.DAY -> Pair(DateUtils.getStartOfDay(date), DateUtils.getEndOfDay(date))
            StatsPeriod.WEEK -> Pair(DateUtils.getStartOfWeek(date), DateUtils.getEndOfWeek(date))
            StatsPeriod.MONTH -> Pair(DateUtils.getStartOfMonth(date), DateUtils.getEndOfMonth(date))
            StatsPeriod.CUSTOM -> Pair(DateUtils.getStartOfDay(customStart), DateUtils.getEndOfDay(customEnd))
        }
    }

    private val periodLabel = combine(_period, _currentDate, _customStartDate, _customEndDate) { period, date, customStart, customEnd ->
        when (period) {
            StatsPeriod.DAY -> DateUtils.formatDate(DateUtils.getStartOfDay(date))
            StatsPeriod.WEEK -> {
                val start = DateUtils.formatDate(DateUtils.getStartOfWeek(date))
                val end = DateUtils.formatDate(DateUtils.getEndOfWeek(date) - 1)
                "$start 〜 $end"
            }
            StatsPeriod.MONTH -> DateUtils.formatMonth(DateUtils.getStartOfMonth(date))
            StatsPeriod.CUSTOM -> {
                val start = DateUtils.formatDate(DateUtils.getStartOfDay(customStart))
                val end = DateUtils.formatDate(DateUtils.getStartOfDay(customEnd))
                "$start 〜 $end"
            }
        }
    }

    private val uiInputs = combine(_period, _currentDate, _customStartDate, _customEndDate) { period, date, customStart, customEnd ->
        UiInputs(period, date, customStart, customEnd)
    }

    val uiState: StateFlow<StatsUiState> = combine(
        uiInputs,
        periodLabel,
        dateRange.flatMapLatest { (start, end) ->
            combine(
                repository.getTotalDistanceByDateRange(start, end),
                repository.getTotalCaloriesByDateRange(start, end),
                repository.getSessionCountByDateRange(start, end),
                repository.getDailyAggregation(start, end),
                repository.getSessionsByDateRange(start, end)
            ) { distance, calories, count, daily, sessions ->
                StatsData(distance, calories, count, daily, sessions)
            }
        }
    ) { inputs, label, data ->
        StatsUiState(
            period = inputs.period,
            currentDate = inputs.currentDate,
            customStartDate = inputs.customStartDate,
            customEndDate = inputs.customEndDate,
            totalDistance = data.totalDistance,
            totalCalories = data.totalCalories,
            sessionCount = data.sessionCount,
            dailyData = data.dailyData,
            sessions = data.sessions,
            periodLabel = label
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        StatsUiState()
    )

    fun setPeriod(period: StatsPeriod) {
        _period.value = period
        _currentDate.value = LocalDate.now()
    }

    fun navigateBack() {
        val date = _currentDate.value
        _currentDate.value = when (_period.value) {
            StatsPeriod.DAY -> date.minusDays(1)
            StatsPeriod.WEEK -> date.minusWeeks(1)
            StatsPeriod.MONTH -> date.minusMonths(1)
            StatsPeriod.CUSTOM -> date // no navigation for custom
        }
    }

    fun navigateForward() {
        val date = _currentDate.value
        _currentDate.value = when (_period.value) {
            StatsPeriod.DAY -> date.plusDays(1)
            StatsPeriod.WEEK -> date.plusWeeks(1)
            StatsPeriod.MONTH -> date.plusMonths(1)
            StatsPeriod.CUSTOM -> date // no navigation for custom
        }
    }

    fun setCustomStartDate(date: LocalDate) {
        _customStartDate.value = date
    }

    fun setCustomEndDate(date: LocalDate) {
        _customEndDate.value = date
    }
}

private data class StatsData(
    val totalDistance: Double,
    val totalCalories: Double,
    val sessionCount: Int,
    val dailyData: List<DailyAggregation>,
    val sessions: List<WalkingSession>
)

private data class UiInputs(
    val period: StatsPeriod,
    val currentDate: LocalDate,
    val customStartDate: LocalDate,
    val customEndDate: LocalDate
)
