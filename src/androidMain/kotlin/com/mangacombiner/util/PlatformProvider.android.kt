package com.mangacombiner.util

import android.content.Context
import android.os.Environment

class AndroidPlatformProvider(private val context: Context) : PlatformProvider {
    override fun getTmpDir(): String = context.cacheDir.absolutePath

    override fun getUserDownloadsDir(): String? {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
    }

    override fun getUserDocumentsDir(): String? {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.absolutePath
    }

    // Android doesn't have a "Desktop" concept
    override fun getUserDesktopDir(): String? = null
}
