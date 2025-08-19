package com.mangacombiner.util

import java.util.Properties

class DesktopAppVersionProvider : AppVersionProvider {
    override fun getAppVersion(): String {
        return try {
            val props = Properties()
            val stream = this.javaClass.getResourceAsStream("/version.properties")
            if (stream != null) {
                props.load(stream)
                props.getProperty("version", "N/A")
            } else {
                "N/A"
            }
        } catch (e: Exception) {
            "N/A"
        }
    }
}

actual fun getAppVersionProvider(): AppVersionProvider = DesktopAppVersionProvider()
