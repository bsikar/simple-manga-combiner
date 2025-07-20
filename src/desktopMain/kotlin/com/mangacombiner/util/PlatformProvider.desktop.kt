package com.mangacombiner.util

import java.awt.Desktop
import java.io.File
import java.util.Locale

class DesktopPlatformProvider(private val customCacheDir: String? = null) : PlatformProvider {
    override fun getTmpDir(): String {
        // If a custom cache dir is provided via CLI, use it as the single source of truth.
        if (!customCacheDir.isNullOrBlank()) {
            val dir = File(customCacheDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir.absolutePath
        }

        // Otherwise, default to a persistent location instead of the OS temp dir
        val os = System.getProperty("os.name", "generic").lowercase(Locale.ENGLISH)
        val userHome = System.getProperty("user.home")
        val appDir = File(
            when {
                "win" in os -> File(System.getenv("LOCALAPPDATA"))
                "mac" in os || "darwin" in os -> File(userHome, "Library/Application Support")
                else -> File(userHome, ".local/share") // Linux XDG standard
            },
            "MangaCombiner"
        )

        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return appDir.absolutePath
    }

    private val userHome = System.getProperty("user.home")
    override fun getUserDownloadsDir(): String? = File(userHome, "Downloads").path
    override fun getUserDocumentsDir(): String? = File(userHome, "Documents").path
    override fun getUserDesktopDir(): String? = File(userHome, "Desktop").path

    override fun getSettingsLocationDescription(): String {
        val os = System.getProperty("os.name", "generic").lowercase(Locale.ENGLISH)
        return when {
            "mac" in os || "darwin" in os -> "Stored as a .plist file in: ~/Library/Preferences/"
            "win" in os -> "Stored in the Windows Registry at: HKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs"
            "nix" in os || "nux" in os || "aix" in os -> "Stored as an XML file in: ~/.java/.userPrefs/"
            else -> "Stored in the default Java Preferences location for your OS."
        }
    }

    override fun isSettingsLocationOpenable(): Boolean {
        val os = System.getProperty("os.name", "generic").lowercase(Locale.ENGLISH)
        // Disable for windows since it's in the registry and not a simple directory.
        return ("mac" in os || "darwin" in os || "nix" in os || "nux" in os || "aix" in os) && Desktop.isDesktopSupported()
    }

    override fun openSettingsLocation() {
        if (!isSettingsLocationOpenable()) {
            Logger.logInfo("Opening settings location is not supported on this OS.")
            return
        }

        val os = System.getProperty("os.name", "generic").lowercase(Locale.ENGLISH)
        val path = when {
            "mac" in os || "darwin" in os -> File(userHome, "Library/Preferences")
            "nix" in os || "nux" in os || "aix" in os -> File(userHome, ".java/.userPrefs")
            else -> null
        }

        try {
            path?.let {
                if (it.exists()) {
                    Desktop.getDesktop().open(it)
                    Logger.logInfo("Opened settings directory: ${it.absolutePath}")
                } else {
                    Logger.logError("Settings directory not found at: ${it.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Logger.logError("Failed to open settings directory.", e)
        }
    }

    override fun isCacheLocationOpenable(): Boolean = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

    override fun openCacheLocation() {
        if (!isCacheLocationOpenable()) {
            Logger.logInfo("Opening the cache location is not supported on this system.")
            return
        }
        try {
            val cacheDir = File(getTmpDir())
            Desktop.getDesktop().open(cacheDir)
            Logger.logInfo("Opened cache directory: ${cacheDir.absolutePath}")
        } catch (e: Exception) {
            Logger.logError("Failed to open cache directory", e)
        }
    }
}
