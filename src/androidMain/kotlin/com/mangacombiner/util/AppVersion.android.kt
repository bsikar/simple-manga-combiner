package com.mangacombiner.util

import android.content.Context
import com.mangacombiner.android.MainActivity

class AndroidAppVersionProvider(private val context: Context) : AppVersionProvider {
    override fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }
}

actual fun getAppVersionProvider(): AppVersionProvider = AndroidAppVersionProvider(MainActivity.INSTANCE.applicationContext)
