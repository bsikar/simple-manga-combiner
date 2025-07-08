package com.mangacombiner.util

import java.awt.Desktop
import java.io.File
import java.util.Locale

class DesktopPlatformProvider : PlatformProvider {
    override fun getTmpDir(): String = System.getProperty("java.io.tmpdir")
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
}
