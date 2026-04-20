package com.healthcare.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object DateUtils {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.JAPAN)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.JAPAN)
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy年MM月", Locale.JAPAN)

    fun formatDate(epochMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        return dateTime.format(dateFormatter)
    }

    fun formatTime(epochMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        return dateTime.format(timeFormatter)
    }

    fun formatDateTime(epochMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        return dateTime.format(dateTimeFormatter)
    }

    fun formatMonth(epochMillis: Long): String {
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        return dateTime.format(monthFormatter)
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            "%.2f km".format(meters / 1000.0)
        } else {
            "%.0f m".format(meters)
        }
    }

    fun getStartOfDay(date: LocalDate = LocalDate.now()): Long {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun getEndOfDay(date: LocalDate = LocalDate.now()): Long {
        return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun getStartOfMonth(date: LocalDate = LocalDate.now()): Long {
        return date.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun getEndOfMonth(date: LocalDate = LocalDate.now()): Long {
        return date.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun getStartOfWeek(date: LocalDate = LocalDate.now()): Long {
        val startOfWeek = date.minusDays(date.dayOfWeek.value.toLong() - 1)
        return startOfWeek.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun getEndOfWeek(date: LocalDate = LocalDate.now()): Long {
        val startOfWeek = date.minusDays(date.dayOfWeek.value.toLong() - 1)
        return startOfWeek.plusWeeks(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun toLocalDate(epochMillis: Long): LocalDate {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    fun getDaysInRange(startMillis: Long, endMillis: Long): Long {
        val start = toLocalDate(startMillis)
        val end = toLocalDate(endMillis)
        return ChronoUnit.DAYS.between(start, end)
    }
}
