package com.mangacombiner.model

import com.mangacombiner.ui.theme.AppTheme

internal expect fun getDefaultPlatformTheme(): AppTheme

data class AppSettings(
    val theme: AppTheme = Defaults.THEME,
    val iconTheme: IconTheme = Defaults.ICON_THEME,
    val defaultOutputLocation: String = Defaults.DEFAULT_OUTPUT_LOCATION,
    val customDefaultOutputPath: String = Defaults.CUSTOM_DEFAULT_OUTPUT_PATH,
    val workers: Int = Defaults.WORKERS,
    val batchWorkers: Int = Defaults.BATCH_WORKERS,
    val outputFormat: String = Defaults.OUTPUT_FORMAT,
    val userAgentName: String = Defaults.USER_AGENT_NAME,
    val perWorkerUserAgent: Boolean = Defaults.PER_WORKER_USER_AGENT,
    val proxyUrl: String = Defaults.PROXY_URL,
    val debugLog: Boolean = Defaults.DEBUG_LOG,
    val logAutoscrollEnabled: Boolean = Defaults.LOG_AUTOSCROLL_ENABLED,
    val zoomFactor: Float = Defaults.ZOOM_FACTOR,
    val fontSizePreset: String = Defaults.FONT_SIZE_PRESET,
    val offlineMode: Boolean = Defaults.OFFLINE_MODE
) {
    /**
     * A companion object that holds all default application settings,
     * providing a single source of truth throughout the app.
     */
    companion object Defaults {
        val THEME = getDefaultPlatformTheme()
        val ICON_THEME = IconTheme.COLOR
        const val DEFAULT_OUTPUT_LOCATION = "Downloads"
        const val CUSTOM_DEFAULT_OUTPUT_PATH = ""
        const val WORKERS = 4
        const val BATCH_WORKERS = 1
        const val OUTPUT_FORMAT = "epub"
        const val USER_AGENT_NAME = "Chrome (Windows)"
        const val PER_WORKER_USER_AGENT = false
        const val PROXY_URL = ""
        const val DEBUG_LOG = false
        const val LOG_AUTOSCROLL_ENABLED = true
        const val ZOOM_FACTOR = 1.0f
        const val FONT_SIZE_PRESET = "Medium"
        const val OFFLINE_MODE = false
    }
}
