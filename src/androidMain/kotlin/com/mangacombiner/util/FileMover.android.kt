package com.mangacombiner.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

actual class FileMover(private val context: Context) {
    actual fun moveToFinalDestination(sourceFile: File, destinationIdentifier: String, finalFileName: String): String {
        // If the destination is not a SAF URI, handle it as a normal file path.
        // This handles cases where the output is set to the app's internal cache.
        if (!destinationIdentifier.startsWith("content://")) {
            return try {
                val destinationDir = File(destinationIdentifier)
                destinationDir.mkdirs()
                val finalFile = File(destinationDir, finalFileName)
                sourceFile.copyTo(finalFile, overwrite = true)
                sourceFile.delete()
                finalFile.absolutePath
            } catch (e: Exception) {
                Logger.logError("Failed to move file to internal directory", e)
                ""
            }
        }

        // Handle the Storage Access Framework (SAF) URI
        val treeUri = Uri.parse(destinationIdentifier)
        val parentDocument = DocumentFile.fromTreeUri(context, treeUri)

        if (parentDocument == null || !parentDocument.canWrite()) {
            Logger.logError("No write permissions for the selected directory.")
            return ""
        }

        // Find if file already exists to delete it first, as some systems don't support overwrite.
        parentDocument.findFile(finalFileName)?.delete()

        // The mime type is needed for createFile
        val mimeType = "application/epub+zip"

        val newFile = parentDocument.createFile(mimeType, finalFileName)
        if (newFile == null) {
            Logger.logError("Failed to create file in the selected directory.")
            return ""
        }

        try {
            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            sourceFile.delete() // Clean up the source file from cache
            return newFile.name ?: finalFileName // Return the display name as confirmation
        } catch (e: Exception) {
            Logger.logError("Failed to copy data to the final destination file", e)
            return ""
        }
    }
}
