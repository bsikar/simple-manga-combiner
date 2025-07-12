package com.mangacombiner.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.mangacombiner.model.IconTheme

actual class IconChanger(private val context: Context) {
    actual fun setIcon(iconTheme: IconTheme) {
        val packageName = context.packageName
        val packageManager = context.packageManager

        val colorAlias = ComponentName(packageName, "$packageName.MainActivityColor")
        val monoAlias = ComponentName(packageName, "$packageName.MainActivityMono")

        val (aliasToEnable, aliasToDisable) = when (iconTheme) {
            IconTheme.COLOR -> colorAlias to monoAlias
            IconTheme.MONO -> monoAlias to colorAlias
        }

        try {
            packageManager.setComponentEnabledSetting(
                aliasToEnable,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                aliasToDisable,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Logger.logInfo("App icon changed to: ${iconTheme.name}")
        } catch (e: Exception) {
            Logger.logError("Failed to change app icon", e)
        }
    }
}
