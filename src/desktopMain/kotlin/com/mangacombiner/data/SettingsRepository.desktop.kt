package com.mangacombiner.data

import com.mangacombiner.model.AppSettings
import com.mangacombiner.ui.theme.AppTheme
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
        private const val DEBUG_LOG = "debug_log"
        private const val LOG_AUTOSCROLL = "log_autoscroll"
        private const val ZOOM_FACTOR = "zoom_factor"
        private const val FONT_SIZE_PRESET = "font_size_preset"
        private const val SYSTEM_LIGHT_THEME = "system_light_theme"
        private const val SYSTEM_DARK_THEME = "system_dark_theme"
    }

    actual fun saveSettings(settings: AppSettings) {
        prefs.put(THEME, settings.theme.name)
        prefs.put(DEFAULT_OUTPUT_LOCATION, settings.defaultOutputLocation)
        prefs.put(CUSTOM_DEFAULT_OUTPUT_PATH, settings.customDefaultOutputPath)
        prefs.putInt(WORKERS, settings.workers)
        prefs.putInt(BATCH_WORKERS, settings.batchWorkers)
        prefs.put(OUTPUT_FORMAT, settings.outputFormat)
        prefs.put(USER_AGENT_NAME, settings.userAgentName)
        prefs.putBoolean(PER_WORKER_USER_AGENT, settings.perWorkerUserAgent)
        prefs.putBoolean(DEBUG_LOG, settings.debugLog)
        prefs.putBoolean(LOG_AUTOSCROLL, settings.logAutoscrollEnabled)
        prefs.putFloat(ZOOM_FACTOR, settings.zoomFactor)
        prefs.put(FONT_SIZE_PRESET, settings.fontSizePreset)
        prefs.put(SYSTEM_LIGHT_THEME, settings.systemLightTheme.name)
        prefs.put(SYSTEM_DARK_THEME, settings.systemDarkTheme.name)
    }

    actual fun loadSettings(): AppSettings {
        return AppSettings(
            theme = AppTheme.valueOf(prefs.get(THEME, AppTheme.LIGHT.name)),
            defaultOutputLocation = prefs.get(DEFAULT_OUTPUT_LOCATION, "Downloads"),
            customDefaultOutputPath = prefs.get(CUSTOM_DEFAULT_OUTPUT_PATH, ""),
            workers = prefs.getInt(WORKERS, 4),
            batchWorkers = prefs.getInt(BATCH_WORKERS, 1),
            outputFormat = prefs.get(OUTPUT_FORMAT, "epub"),
            userAgentName = prefs.get(USER_AGENT_NAME, "Chrome (Windows)"),
            perWorkerUserAgent = prefs.getBoolean(PER_WORKER_USER_AGENT, false),
            debugLog = prefs.getBoolean(DEBUG_LOG, false),
            logAutoscrollEnabled = prefs.getBoolean(LOG_AUTOSCROLL, true),
            zoomFactor = prefs.getFloat(ZOOM_FACTOR, 1.0f),
            fontSizePreset = prefs.get(FONT_SIZE_PRESET, "Medium"),
            systemLightTheme = AppTheme.valueOf(prefs.get(SYSTEM_LIGHT_THEME, AppTheme.LIGHT.name)),
            systemDarkTheme = AppTheme.valueOf(prefs.get(SYSTEM_DARK_THEME, AppTheme.DARK.name))
        )
    }
}
