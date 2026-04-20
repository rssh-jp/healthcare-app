package com.healthcare.app.util

import com.healthcare.app.BuildConfig

object MapsApiKeyValidator {

    private val placeholders = setOf(
        "YOUR_API_KEY_HERE",
        "your_actual_api_key_here"
    )

    fun isConfigured(): Boolean {
        val key = BuildConfig.MAPS_API_KEY.trim()
        return key.isNotEmpty() && placeholders.none { it.equals(key, ignoreCase = true) }
    }
}
