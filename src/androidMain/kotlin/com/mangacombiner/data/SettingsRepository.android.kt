package com.mangacombiner.data

import android.content.Context
import androidx.core.content.edit
import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.ProxyType
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.util.Logger

actual class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        private const val THEME = "theme"
        private const val DEFAULT_OUTPUT_LOCATION = "default_output_location"
        private const val CUSTOM_DEFAULT_OUTPUT_PATH = "custom_default_output_path"
        private const val WORKERS = "workers"
        private const val BATCH_WORKERS = "batch_workers"
        private const val OUTPUT_FORMAT = "output_format"
        private const val USER_AGENT_NAME = "user_agent_name"
        private const val PER_WORKER_USER_AGENT = "per_worker_user_agent"
        private const val PROXY_URL = "proxy_url"
        private const val PROXY_TYPE = "proxy_type"
        private const val PROXY_HOST = "proxy_host"
        private const val PROXY_PORT = "proxy_port"
        private const val PROXY_USER = "proxy_user"
        private const val PROXY_PASS = "proxy_pass"
        private const val DEBUG_LOG = "debug_log"
        private const val LOG_AUTOSCROLL = "log_autoscroll"
        private const val ZOOM_FACTOR = "zoom_factor"
        private const val FONT_SIZE_PRESET = "font_size_preset"
        private const val OFFLINE_MODE = "offline_mode"
        private const val ALLOW_NSFW = "allow_nsfw" // New key
        private const val PROXY_ENABLED_ON_STARTUP = "proxy_enabled_on_startup"
        private const val IP_LOOKUP_URL = "ip_lookup_url"
        private const val CUSTOM_IP_LOOKUP_URL = "custom_ip_lookup_url"
        private const val LIBRARY_SCAN_PATHS = "library_scan_paths"
    }

    actual fun saveSettings(settings: AppSettings) {
        Logger.logDebug { "Saving settings to Android SharedPreferences." }
        prefs.edit(commit = true) {
            putString(THEME, settings.theme.name)
            putString(DEFAULT_OUTPUT_LOCATION, settings.defaultOutputLocation)
            putString(CUSTOM_DEFAULT_OUTPUT_PATH, settings.customDefaultOutputPath)
            putInt(WORKERS, settings.workers)
            putInt(BATCH_WORKERS, settings.batchWorkers)
            putString(OUTPUT_FORMAT, settings.outputFormat)
            putString(USER_AGENT_NAME, settings.userAgentName)
            putBoolean(PER_WORKER_USER_AGENT, settings.perWorkerUserAgent)
            putString(PROXY_URL, settings.proxyUrl)
            putString(PROXY_TYPE, settings.proxyType.name)
            putString(PROXY_HOST, settings.proxyHost)
            putString(PROXY_PORT, settings.proxyPort)
            putString(PROXY_USER, settings.proxyUser)
            putString(PROXY_PASS, settings.proxyPass)
            putBoolean(DEBUG_LOG, settings.debugLog)
            putBoolean(LOG_AUTOSCROLL, settings.logAutoscrollEnabled)
            putFloat(ZOOM_FACTOR, settings.zoomFactor)
            putString(FONT_SIZE_PRESET, settings.fontSizePreset)
            putBoolean(OFFLINE_MODE, settings.offlineMode)
            putBoolean(ALLOW_NSFW, settings.allowNsfw)
            putBoolean(PROXY_ENABLED_ON_STARTUP, settings.proxyEnabledOnStartup)
            putString(IP_LOOKUP_URL, settings.ipLookupUrl)
            putString(CUSTOM_IP_LOOKUP_URL, settings.customIpLookupUrl)
            putStringSet(LIBRARY_SCAN_PATHS, settings.libraryScanPaths)
        }
    }

    actual fun loadSettings(): AppSettings {
        Logger.logDebug { "Loading settings from Android SharedPreferences." }
        val defaultSettings = AppSettings()
        val savedThemeName = prefs.getString(THEME, defaultSettings.theme.name)
        val savedProxyTypeName = prefs.getString(PROXY_TYPE, defaultSettings.proxyType.name)

        return AppSettings(
            theme = AppTheme.entries.find { it.name == savedThemeName } ?: defaultSettings.theme,
            defaultOutputLocation = prefs.getString(DEFAULT_OUTPUT_LOCATION, defaultSettings.defaultOutputLocation) ?: defaultSettings.defaultOutputLocation,
            customDefaultOutputPath = prefs.getString(CUSTOM_DEFAULT_OUTPUT_PATH, defaultSettings.customDefaultOutputPath) ?: defaultSettings.customDefaultOutputPath,
            workers = prefs.getInt(WORKERS, defaultSettings.workers),
            batchWorkers = prefs.getInt(BATCH_WORKERS, defaultSettings.batchWorkers),
            outputFormat = prefs.getString(OUTPUT_FORMAT, defaultSettings.outputFormat) ?: defaultSettings.outputFormat,
            userAgentName = prefs.getString(USER_AGENT_NAME, defaultSettings.userAgentName) ?: defaultSettings.userAgentName,
            perWorkerUserAgent = prefs.getBoolean(PER_WORKER_USER_AGENT, defaultSettings.perWorkerUserAgent),
            proxyUrl = prefs.getString(PROXY_URL, defaultSettings.proxyUrl) ?: defaultSettings.proxyUrl,
            proxyType = ProxyType.entries.find { it.name == savedProxyTypeName } ?: defaultSettings.proxyType,
            proxyHost = prefs.getString(PROXY_HOST, defaultSettings.proxyHost) ?: defaultSettings.proxyHost,
            proxyPort = prefs.getString(PROXY_PORT, defaultSettings.proxyPort) ?: defaultSettings.proxyPort,
            proxyUser = prefs.getString(PROXY_USER, defaultSettings.proxyUser) ?: defaultSettings.proxyUser,
            proxyPass = prefs.getString(PROXY_PASS, defaultSettings.proxyPass) ?: defaultSettings.proxyPass,
            debugLog = prefs.getBoolean(DEBUG_LOG, defaultSettings.debugLog),
            logAutoscrollEnabled = prefs.getBoolean(LOG_AUTOSCROLL, defaultSettings.logAutoscrollEnabled),
            zoomFactor = prefs.getFloat(ZOOM_FACTOR, defaultSettings.zoomFactor),
            fontSizePreset = prefs.getString(FONT_SIZE_PRESET, defaultSettings.fontSizePreset) ?: defaultSettings.fontSizePreset,
            offlineMode = prefs.getBoolean(OFFLINE_MODE, defaultSettings.offlineMode),
            allowNsfw = prefs.getBoolean(ALLOW_NSFW, defaultSettings.allowNsfw),
            proxyEnabledOnStartup = prefs.getBoolean(PROXY_ENABLED_ON_STARTUP, defaultSettings.proxyEnabledOnStartup),
            ipLookupUrl = prefs.getString(IP_LOOKUP_URL, defaultSettings.ipLookupUrl) ?: defaultSettings.ipLookupUrl,
            customIpLookupUrl = prefs.getString(CUSTOM_IP_LOOKUP_URL, defaultSettings.customIpLookupUrl) ?: defaultSettings.customIpLookupUrl,
            libraryScanPaths = prefs.getStringSet(LIBRARY_SCAN_PATHS, defaultSettings.libraryScanPaths) ?: defaultSettings.libraryScanPaths
        )
    }
}
