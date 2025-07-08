package com.mangacombiner.util

import android.content.Context

class AndroidPlatformProvider(private val context: Context) : PlatformProvider {
    override fun getTmpDir(): String = context.cacheDir.absolutePath
}
