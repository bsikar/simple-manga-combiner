package com.mangacombiner.util

import android.content.Context
import android.os.Environment

class AndroidPlatformProvider(private val context: Context) : PlatformProvider {
    override fun getTmpDir(): String = context.cacheDir.absolutePath
    override fun getUserDownloadsDir(): String? =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
    override fun getUserDocumentsDir(): String? =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.absolutePath
    override fun getUserDesktopDir(): String? = null

    override fun getSettingsLocationDescription(): String {
        val prefsFile = "app_settings.xml"
        return "Stored in the app's private data directory: /data/data/${context.packageName}/shared_prefs/$prefsFile"
    }

    override fun isSettingsLocationOpenable(): Boolean {
        // It's not possible for the user to open this location on a non-rooted device.
        return false
    }

    override fun openSettingsLocation() {
        Logger.logInfo("Opening the settings location is not possible on Android from within the app.")
    }

    override fun isCacheLocationOpenable(): Boolean = false

    override fun openCacheLocation() { Logger.logInfo("Opening the cache location is not supported on Android.") }
}
