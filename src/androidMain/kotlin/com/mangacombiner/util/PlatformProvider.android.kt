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
}
