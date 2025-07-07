package com.mangacombiner.service

import com.mangacombiner.util.Logger
import com.mangacombiner.util.getTmpDir
import java.io.File

/**
 * A service object for managing the application's cache.
 */
object CacheService {

    /**
     * A list of directory prefixes used by the application for temporary storage.
     * This helps in identifying which folders in the system's temp directory belong to this app.
     */
    private val appDirPrefixes = listOf(
        "manga-dl-",
        "cbz-reprocess-",
        "epub-reprocess-",
        "cbz-to-epub-",
        "epub-to-cbz-"
    )

    /**
     * Scans the system's temporary directory for folders created by this application and deletes them.
     * It logs the result, including the amount of space freed.
     */
    fun clearAppCache() {
        Logger.logInfo("Clearing application cache...")
        try {
            val temp = File(getTmpDir())
            if (!temp.exists() || !temp.isDirectory) {
                Logger.logError("Temporary directory does not exist: ${getTmpDir()}")
                return
            }

            // Find all directories in the temp folder that match the app's known prefixes
            val dirsToDelete = temp.listFiles { file ->
                file.isDirectory && appDirPrefixes.any { prefix -> file.name.startsWith(prefix) }
            } ?: emptyArray()

            if (dirsToDelete.isEmpty()) {
                Logger.logInfo("No cache directories found to clear.")
                return
            }

            var totalDeletedBytes = 0L
            dirsToDelete.forEach { dir ->
                val size = dir.walk().map { it.length() }.sum()
                if (dir.deleteRecursively()) {
                    Logger.logDebug { "Deleted cache directory: ${dir.name}" }
                    totalDeletedBytes += size
                } else {
                    Logger.logError("Failed to delete directory: ${dir.absolutePath}")
                }
            }
            // Format the total bytes into a human-readable MB string
            val deletedMB = "%.2f".format(totalDeletedBytes / (1024.0 * 1024.0))
            Logger.logInfo("Cache cleared successfully. Freed approximately $deletedMB MB.")

        } catch (e: Exception) {
            Logger.logError("An error occurred while clearing the cache.", e)
        }
    }
}
