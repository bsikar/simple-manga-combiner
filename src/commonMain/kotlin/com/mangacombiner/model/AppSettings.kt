package com.mangacombiner.model

import com.mangacombiner.ui.theme.AppTheme

internal expect fun getDefaultPlatformTheme(): AppTheme

enum class ProxyType {
    NONE, HTTP, SOCKS5
}

data class AppSettings(
    val theme: AppTheme = Defaults.THEME,
    val defaultOutputLocation: String = Defaults.DEFAULT_OUTPUT_LOCATION,
    val customDefaultOutputPath: String = Defaults.CUSTOM_DEFAULT_OUTPUT_PATH,
    val workers: Int = Defaults.WORKERS,
    val batchWorkers: Int = Defaults.BATCH_WORKERS,
    val outputFormat: String = Defaults.OUTPUT_FORMAT,
    val userAgentName: String = Defaults.USER_AGENT_NAME,
    val perWorkerUserAgent: Boolean = Defaults.PER_WORKER_USER_AGENT,
    val proxyUrl: String = Defaults.PROXY_URL,
    val proxyType: ProxyType = Defaults.PROXY_TYPE,
    val proxyHost: String = Defaults.PROXY_HOST,
    val proxyPort: String = Defaults.PROXY_PORT,
    val proxyUser: String = Defaults.PROXY_USER,
    val proxyPass: String = Defaults.PROXY_PASS,
    val debugLog: Boolean = Defaults.DEBUG_LOG,
    val logAutoscrollEnabled: Boolean = Defaults.LOG_AUTOSCROLL_ENABLED,
    val zoomFactor: Float = Defaults.ZOOM_FACTOR,
    val fontSizePreset: String = Defaults.FONT_SIZE_PRESET,
    val offlineMode: Boolean = Defaults.OFFLINE_MODE,
    val proxyEnabledOnStartup: Boolean = Defaults.PROXY_ENABLED_ON_STARTUP,
    val ipLookupUrl: String = Defaults.IP_LOOKUP_URL,
    val customIpLookupUrl: String = Defaults.CUSTOM_IP_LOOKUP_URL,
    val libraryScanPaths: Set<String> = Defaults.LIBRARY_SCAN_PATHS
) {
    /**
     * A companion object that holds all default application settings,
     * providing a single source of truth throughout the app.
     */
    companion object Defaults {
        val THEME = getDefaultPlatformTheme()
        const val DEFAULT_OUTPUT_LOCATION = "Downloads"
        const val CUSTOM_DEFAULT_OUTPUT_PATH = ""
        const val WORKERS = 4
        const val BATCH_WORKERS = 1
        const val OUTPUT_FORMAT = "epub"
        const val USER_AGENT_NAME = "Chrome (Windows)"
        const val PER_WORKER_USER_AGENT = false
        const val PROXY_URL = ""
        val PROXY_TYPE = ProxyType.NONE
        const val PROXY_HOST = ""
        const val PROXY_PORT = ""
        const val PROXY_USER = ""
        const val PROXY_PASS = ""
        const val DEBUG_LOG = false
        const val LOG_AUTOSCROLL_ENABLED = true
        const val ZOOM_FACTOR = 1.0f
        const val FONT_SIZE_PRESET = "Medium"
        const val OFFLINE_MODE = false
        const val PROXY_ENABLED_ON_STARTUP = false
        const val IP_LOOKUP_URL = "https://ipinfo.io/json"
        const val CUSTOM_IP_LOOKUP_URL = ""
        val LIBRARY_SCAN_PATHS: Set<String> = emptySet()
    }
}
