package com.mangacombiner.model

import com.mangacombiner.ui.theme.AppTheme

/**
 * A data class representing the user's saved preferences.
 */
data class AppSettings(
    val theme: AppTheme = AppTheme.LIGHT,
    val defaultOutputLocation: String = "Downloads",
    val customDefaultOutputPath: String = "",
    val workers: Int = 4,
    val outputFormat: String = "epub",
    val userAgentName: String = "Chrome (Windows)",
    val perWorkerUserAgent: Boolean = false,
    val debugLog: Boolean = false,
    val logAutoscrollEnabled: Boolean = true,
    val zoomFactor: Float = 1.0f,
    val fontSizePreset: String = "Medium",
    val systemLightTheme: AppTheme = AppTheme.LIGHT,
    val systemDarkTheme: AppTheme = AppTheme.DARK
)
