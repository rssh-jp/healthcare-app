package com.healthcare.app.util

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalorieCalculator @Inject constructor() {

    companion object {
        const val DEFAULT_WEIGHT_KG = 65.0

        // MET values for different walking/running speeds
        private const val MET_SLOW_WALK = 2.5       // < 1.0 m/s (~3.6 km/h)
        private const val MET_NORMAL_WALK = 3.5      // 1.0-1.5 m/s (~3.6-5.4 km/h)
        private const val MET_BRISK_WALK = 4.5       // 1.5-2.0 m/s (~5.4-7.2 km/h)
        private const val MET_FAST_WALK = 5.0        // 2.0-2.5 m/s (~7.2-9.0 km/h)
        private const val MET_JOG = 7.0              // 2.5-3.0 m/s (~9.0-10.8 km/h)
        private const val MET_RUN = 9.0              // > 3.0 m/s (~10.8+ km/h)
    }

    /**
     * Calculate calories burned.
     * Formula: Calories = MET × weight(kg) × duration(hours)
     * @param speedMps average speed in meters per second
     * @param durationSeconds duration in seconds
     * @param weightKg body weight in kg
     * @return estimated calories burned (kcal)
     */
    fun calculateCalories(
        speedMps: Double,
        durationSeconds: Double,
        weightKg: Double = DEFAULT_WEIGHT_KG
    ): Double {
        if (durationSeconds <= 0 || weightKg <= 0) return 0.0
        val met = getMet(speedMps)
        val durationHours = durationSeconds / 3600.0
        return met * weightKg * durationHours
    }

    /**
     * Calculate calories for a distance segment between two GPS points.
     * @param distanceMeters distance in meters
     * @param timeDeltaSeconds time elapsed in seconds
     * @param weightKg body weight in kg
     * @return estimated calories burned (kcal)
     */
    fun calculateCaloriesForSegment(
        distanceMeters: Double,
        timeDeltaSeconds: Double,
        weightKg: Double = DEFAULT_WEIGHT_KG
    ): Double {
        if (timeDeltaSeconds <= 0 || distanceMeters <= 0) return 0.0
        val speedMps = distanceMeters / timeDeltaSeconds
        return calculateCalories(speedMps, timeDeltaSeconds, weightKg)
    }

    private fun getMet(speedMps: Double): Double {
        return when {
            speedMps < 1.0 -> MET_SLOW_WALK
            speedMps < 1.5 -> MET_NORMAL_WALK
            speedMps < 2.0 -> MET_BRISK_WALK
            speedMps < 2.5 -> MET_FAST_WALK
            speedMps < 3.0 -> MET_JOG
            else -> MET_RUN
        }
    }

    /**
     * Format calories for display
     */
    fun formatCalories(calories: Double): String {
        return if (calories < 1.0) {
            "%.1f".format(calories)
        } else {
            "%.0f".format(calories)
        }
    }
}
