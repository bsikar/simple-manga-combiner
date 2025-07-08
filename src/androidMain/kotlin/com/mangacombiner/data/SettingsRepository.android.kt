package com.mangacombiner.data

import android.content.Context
import com.mangacombiner.model.AppSettings
import com.mangacombiner.ui.theme.AppTheme

actual class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        private const val THEME = "theme"
        private const val DEFAULT_OUTPUT_LOCATION = "default_output_location"
        private const val CUSTOM_DEFAULT_OUTPUT_PATH = "custom_default_output_path"
        private const val WORKERS = "workers"
        private const val OUTPUT_FORMAT = "output_format"
        private const val USER_AGENT_NAME = "user_agent_name"
        private const val PER_WORKER_USER_AGENT = "per_worker_user_agent"
        private const val DEBUG_LOG = "debug_log"
        private const val LOG_AUTOSCROLL = "log_autoscroll"
    }

    actual fun saveSettings(settings: AppSettings) {
        with(prefs.edit()) {
            putString(THEME, settings.theme.name)
            putString(DEFAULT_OUTPUT_LOCATION, settings.defaultOutputLocation)
            putString(CUSTOM_DEFAULT_OUTPUT_PATH, settings.customDefaultOutputPath)
            putInt(WORKERS, settings.workers)
            putString(OUTPUT_FORMAT, settings.outputFormat)
            putString(USER_AGENT_NAME, settings.userAgentName)
            putBoolean(PER_WORKER_USER_AGENT, settings.perWorkerUserAgent)
            putBoolean(DEBUG_LOG, settings.debugLog)
            putBoolean(LOG_AUTOSCROLL, settings.logAutoscrollEnabled)
            apply()
        }
    }

    actual fun loadSettings(): AppSettings {
        return AppSettings(
            theme = AppTheme.valueOf(prefs.getString(THEME, AppTheme.DARK.name) ?: AppTheme.DARK.name),
            defaultOutputLocation = prefs.getString(DEFAULT_OUTPUT_LOCATION, "Downloads") ?: "Downloads",
            customDefaultOutputPath = prefs.getString(CUSTOM_DEFAULT_OUTPUT_PATH, "") ?: "",
            workers = prefs.getInt(WORKERS, 4),
            outputFormat = prefs.getString(OUTPUT_FORMAT, "epub") ?: "epub",
            userAgentName = prefs.getString(USER_AGENT_NAME, "Chrome (Windows)") ?: "Chrome (Windows)",
            perWorkerUserAgent = prefs.getBoolean(PER_WORKER_USER_AGENT, false),
            debugLog = prefs.getBoolean(DEBUG_LOG, false),
            logAutoscrollEnabled = prefs.getBoolean(LOG_AUTOSCROLL, true)
        )
    }
}
