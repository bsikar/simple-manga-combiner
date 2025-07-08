package com.mangacombiner.service

import com.mangacombiner.util.CachedChapterNameComparator
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.titlecase
import java.io.File
import java.io.FileFilter

data class CachedChapter(
    val name: String,
    val path: String,
    val sizeFormatted: String,
    val sizeInBytes: Long,
    val pageCount: Int,
    val parentPath: String
)

data class CachedSeries(
    val seriesName: String,
    val path: String,
    val chapters: List<CachedChapter>,
    val totalSizeFormatted: String
)

/**
 * A service class for managing the application's cache of partial downloads.
 */
class CacheService(private val platformProvider: PlatformProvider) {

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Scans the temporary directory and returns a structured list of cached series and their chapters.
     */
    fun getCacheContents(): List<CachedSeries> {
        val temp = File(platformProvider.getTmpDir())
        if (!temp.exists() || !temp.isDirectory) return emptyList()

        return temp.listFiles(FileFilter { file ->
            file.isDirectory && file.name.startsWith("manga-dl-")
        })?.map { seriesDir ->
            val chapters = seriesDir.listFiles(FileFilter { it.isDirectory })?.mapNotNull { chapterDir ->
                val files = chapterDir.walk().filter { it.isFile }.toList()
                // Only count directories that contain at least one valid image file
                if (files.any { it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS }) {
                    val sizeInBytes = files.sumOf { it.length() }
                    CachedChapter(
                        name = chapterDir.name,
                        path = chapterDir.absolutePath,
                        sizeFormatted = formatSize(sizeInBytes),
                        sizeInBytes = sizeInBytes,
                        pageCount = files.count { it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS },
                        parentPath = seriesDir.absolutePath
                    )
                } else {
                    null
                }
            }?.sortedWith(CachedChapterNameComparator) ?: emptyList()

            CachedSeries(
                seriesName = seriesDir.name.removePrefix("manga-dl-").replace('-', ' ').titlecase(),
                path = seriesDir.absolutePath,
                chapters = chapters,
                totalSizeFormatted = formatSize(seriesDir.walk().sumOf { it.length() })
            )
        }?.sortedBy { it.seriesName } ?: emptyList()
    }

    /**
     * Deletes a list of specified files or directories.
     */
    fun deleteCacheItems(pathsToDelete: List<String>) {
        if (pathsToDelete.isEmpty()) {
            Logger.logInfo("No cache items selected for deletion.")
            return
        }
        Logger.logInfo("Deleting ${pathsToDelete.size} selected cache item(s)...")
        var successCount = 0
        pathsToDelete.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                if (file.deleteRecursively()) {
                    Logger.logDebug { "Deleted: $path" }
                    successCount++
                } else {
                    Logger.logError("Failed to delete: $path")
                }
            }
        }
        Logger.logInfo("Successfully deleted $successCount item(s).")
    }

    /**
     * Deletes all temporary application data.
     */
    fun clearAllAppCache() {
        Logger.logInfo("Clearing all application cache...")
        val allSeries = getCacheContents()
        if (allSeries.isEmpty()) {
            Logger.logInfo("No cache directories found to clear.")
            return
        }
        deleteCacheItems(allSeries.map { it.path })
    }
}
