package com.mangacombiner.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class UpdateDownloader {
    actual fun downloadAndInstall(url: String, onDownloaded: (String) -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File.createTempFile("manga-combiner-update", ".jar")
                URL(url).openStream().use { input ->
                    Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                onDownloaded(tempFile.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
