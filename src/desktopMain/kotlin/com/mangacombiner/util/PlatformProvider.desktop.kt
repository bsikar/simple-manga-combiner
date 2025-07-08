package com.mangacombiner.util

import java.io.File

class DesktopPlatformProvider : PlatformProvider {
    override fun getTmpDir(): String = System.getProperty("java.io.tmpdir")

    private val userHome = System.getProperty("user.home")

    override fun getUserDownloadsDir(): String? = File(userHome, "Downloads").path

    override fun getUserDocumentsDir(): String? = File(userHome, "Documents").path

    override fun getUserDesktopDir(): String? = File(userHome, "Desktop").path
}
