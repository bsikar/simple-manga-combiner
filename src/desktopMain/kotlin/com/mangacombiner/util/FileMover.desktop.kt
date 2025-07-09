package com.mangacombiner.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class FileMover {
    actual fun moveToFinalDestination(sourceFile: File, destinationIdentifier: String, finalFileName: String): String {
        return try {
            val destinationDir = File(destinationIdentifier)
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }
            val finalFile = File(destinationDir, finalFileName)

            Files.move(sourceFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            finalFile.absolutePath
        } catch (e: Exception) {
            Logger.logError("Failed to move file to final destination", e)
            ""
        }
    }
}
