package com.mangacombiner.data

import com.mangacombiner.model.AppSettings

/**
 * An expect class that defines the contract for a platform-specific
 * repository responsible for saving and loading user settings.
 */
expect class SettingsRepository {
    fun saveSettings(settings: AppSettings)
    fun loadSettings(): AppSettings
}
