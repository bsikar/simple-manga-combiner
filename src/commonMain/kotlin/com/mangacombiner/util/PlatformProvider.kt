package com.mangacombiner.util

/**
 * Provides platform-specific utilities like directory paths.
 * This is injected via Koin to decouple services from platform specifics.
 */
interface PlatformProvider {
    fun getTmpDir(): String
    fun getUserDownloadsDir(): String?
    fun getUserDocumentsDir(): String?
    fun getUserDesktopDir(): String?
    fun getSettingsLocationDescription(): String
    fun isSettingsLocationOpenable(): Boolean
    fun openSettingsLocation()
}
