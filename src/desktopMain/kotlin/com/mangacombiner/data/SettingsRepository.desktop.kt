package com.mangacombiner.data

import com.mangacombiner.model.AppSettings
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.util.Logger
import java.util.prefs.Preferences

actual class SettingsRepository {
    private val prefs = Preferences.userNodeForPackage(AppSettings::class.java)

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
        private const val DEBUG_LOG = "debug_log"
        private const val LOG_AUTOSCROLL = "log_autoscroll"
        private const val ZOOM_FACTOR = "zoom_factor"
        private const val FONT_SIZE_PRESET = "font_size_preset"
        private const val OFFLINE_MODE = "offline_mode"
    }

    actual fun saveSettings(settings: AppSettings) {
        Logger.logDebug { "Saving settings to Java Preferences." }
        prefs.put(THEME, settings.theme.name)
        prefs.put(DEFAULT_OUTPUT_LOCATION, settings.defaultOutputLocation)
        prefs.put(CUSTOM_DEFAULT_OUTPUT_PATH, settings.customDefaultOutputPath)
        prefs.putInt(WORKERS, settings.workers)
        prefs.putInt(BATCH_WORKERS, settings.batchWorkers)
        prefs.put(OUTPUT_FORMAT, settings.outputFormat)
        prefs.put(USER_AGENT_NAME, settings.userAgentName)
        prefs.putBoolean(PER_WORKER_USER_AGENT, settings.perWorkerUserAgent)
        prefs.put(PROXY_URL, settings.proxyUrl)
        prefs.putBoolean(DEBUG_LOG, settings.debugLog)
        prefs.putBoolean(LOG_AUTOSCROLL, settings.logAutoscrollEnabled)
        prefs.putFloat(ZOOM_FACTOR, settings.zoomFactor)
        prefs.put(FONT_SIZE_PRESET, settings.fontSizePreset)
        prefs.putBoolean(OFFLINE_MODE, settings.offlineMode)
    }

    actual fun loadSettings(): AppSettings {
        Logger.logDebug { "Loading settings from Java Preferences." }
        val defaultSettings = AppSettings()
        val savedThemeName = prefs.get(THEME, defaultSettings.theme.name)

        return AppSettings(
            theme = AppTheme.entries.find { it.name == savedThemeName } ?: defaultSettings.theme,
            defaultOutputLocation = prefs.get(DEFAULT_OUTPUT_LOCATION, defaultSettings.defaultOutputLocation),
            customDefaultOutputPath = prefs.get(CUSTOM_DEFAULT_OUTPUT_PATH, defaultSettings.customDefaultOutputPath),
            workers = prefs.getInt(WORKERS, defaultSettings.workers),
            batchWorkers = prefs.getInt(BATCH_WORKERS, defaultSettings.batchWorkers),
            outputFormat = prefs.get(OUTPUT_FORMAT, defaultSettings.outputFormat),
            userAgentName = prefs.get(USER_AGENT_NAME, defaultSettings.userAgentName),
            perWorkerUserAgent = prefs.getBoolean(PER_WORKER_USER_AGENT, defaultSettings.perWorkerUserAgent),
            proxyUrl = prefs.get(PROXY_URL, defaultSettings.proxyUrl),
            debugLog = prefs.getBoolean(DEBUG_LOG, defaultSettings.debugLog),
            logAutoscrollEnabled = prefs.getBoolean(LOG_AUTOSCROLL, defaultSettings.logAutoscrollEnabled),
            zoomFactor = prefs.getFloat(ZOOM_FACTOR, defaultSettings.zoomFactor),
            fontSizePreset = prefs.get(FONT_SIZE_PRESET, defaultSettings.fontSizePreset),
            offlineMode = prefs.getBoolean(OFFLINE_MODE, defaultSettings.offlineMode)
        )
    }
}
