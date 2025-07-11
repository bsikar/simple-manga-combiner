package com.mangacombiner.data

import android.content.Context
import androidx.core.content.edit
import com.mangacombiner.model.AppSettings
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
        private const val DEBUG_LOG = "debug_log"
        private const val LOG_AUTOSCROLL = "log_autoscroll"
        private const val ZOOM_FACTOR = "zoom_factor"
        private const val FONT_SIZE_PRESET = "font_size_preset"
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
            putBoolean(DEBUG_LOG, settings.debugLog)
            putBoolean(LOG_AUTOSCROLL, settings.logAutoscrollEnabled)
            putFloat(ZOOM_FACTOR, settings.zoomFactor)
            putString(FONT_SIZE_PRESET, settings.fontSizePreset)
        }
    }

    actual fun loadSettings(): AppSettings {
        Logger.logDebug { "Loading settings from Android SharedPreferences." }
        return AppSettings(
            theme = AppTheme.valueOf(prefs.getString(THEME, AppTheme.LIGHT.name) ?: AppTheme.LIGHT.name),
            defaultOutputLocation = prefs.getString(DEFAULT_OUTPUT_LOCATION, "Downloads") ?: "Downloads",
            customDefaultOutputPath = prefs.getString(CUSTOM_DEFAULT_OUTPUT_PATH, "") ?: "",
            workers = prefs.getInt(WORKERS, 4),
            batchWorkers = prefs.getInt(BATCH_WORKERS, 1),
            outputFormat = prefs.getString(OUTPUT_FORMAT, "epub") ?: "epub",
            userAgentName = prefs.getString(USER_AGENT_NAME, "Chrome (Windows)") ?: "Chrome (Windows)",
            perWorkerUserAgent = prefs.getBoolean(PER_WORKER_USER_AGENT, false),
            proxyUrl = prefs.getString(PROXY_URL, "") ?: "",
            debugLog = prefs.getBoolean(DEBUG_LOG, false),
            logAutoscrollEnabled = prefs.getBoolean(LOG_AUTOSCROLL, true),
            zoomFactor = prefs.getFloat(ZOOM_FACTOR, 1.0f),
            fontSizePreset = prefs.getString(FONT_SIZE_PRESET, "Medium") ?: "Medium"
        )
    }
}
