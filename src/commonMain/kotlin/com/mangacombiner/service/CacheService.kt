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
    val parentPath: String,
    val isBroken: Boolean
)

data class CachedSeries(
    val seriesName: String,
    val path: String,
    val seriesUrl: String?,
    val chapters: List<CachedChapter>,
    val totalSizeFormatted: String,
    val plannedChapterCount: Int?
)

class CacheService(
    private val platformProvider: PlatformProvider,
    private val persistenceService: QueuePersistenceService
) {

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    fun getCacheContents(): List<CachedSeries> {
        Logger.logDebug { "Reading cache contents from: ${platformProvider.getTmpDir()}" }
        val temp = File(platformProvider.getTmpDir())
        if (!temp.exists() || !temp.isDirectory) {
            Logger.logDebug { "Cache directory does not exist." }
            return emptyList()
        }

        return temp.listFiles(FileFilter { file ->
            file.isDirectory && file.name.startsWith("manga-dl-")
        })?.map { seriesDir ->
            Logger.logDebug { "Found cached series directory: ${seriesDir.name}" }
            val chapters = seriesDir.listFiles(FileFilter { it.isDirectory })?.mapNotNull { chapterDir ->
                val files = chapterDir.walk().filter { it.isFile }.toList()
                if (files.any { it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS }) {
                    val sizeInBytes = files.sumOf { it.length() }
                    val isBroken = File(chapterDir, ".incomplete").exists()
                    Logger.logDebug { "Found cached chapter: ${chapterDir.name}, Size: $sizeInBytes, Broken: $isBroken" }
                    CachedChapter(
                        name = chapterDir.name,
                        path = chapterDir.absolutePath,
                        sizeFormatted = formatSize(sizeInBytes),
                        sizeInBytes = sizeInBytes,
                        pageCount = files.count { it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS },
                        parentPath = seriesDir.absolutePath,
                        isBroken = isBroken
                    )
                } else {
                    Logger.logDebug { "Skipping empty or invalid chapter directory: ${chapterDir.name}" }
                    null
                }
            }?.sortedWith(CachedChapterNameComparator) ?: emptyList()

            val urlFile = File(seriesDir, "url.txt")
            val metadata = persistenceService.loadOperationMetadata(seriesDir.absolutePath)
            val seriesUrl = metadata?.seriesUrl ?: if (urlFile.exists()) urlFile.readText().trim() else null
            Logger.logDebug { "Series URL for ${seriesDir.name}: $seriesUrl" }

            CachedSeries(
                seriesName = metadata?.customTitle ?: seriesDir.name.removePrefix("manga-dl-").replace('-', ' ').titlecase(),
                path = seriesDir.absolutePath,
                seriesUrl = seriesUrl,
                chapters = chapters,
                totalSizeFormatted = formatSize(seriesDir.walk().sumOf { it.length() }),
                plannedChapterCount = metadata?.chapters?.size
            )
        }?.sortedBy { it.seriesName } ?: emptyList()
    }

    fun getCachedChapterStatus(seriesSlug: String): Map<String, Boolean> {
        val seriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")
        Logger.logDebug { "Checking cache status for series slug '$seriesSlug' in directory: ${seriesDir.absolutePath}" }
        if (!seriesDir.exists() || !seriesDir.isDirectory) {
            Logger.logDebug { "Series cache directory not found for slug: $seriesSlug" }
            return emptyMap()
        }
        return seriesDir.listFiles(FileFilter { it.isDirectory })
            ?.associate { chapterDir ->
                val isComplete = !File(chapterDir, ".incomplete").exists()
                Logger.logDebug { "Cached chapter '${chapterDir.name}' is complete: $isComplete" }
                chapterDir.name to isComplete
            } ?: emptyMap()
    }

    fun deleteCacheItems(pathsToDelete: List<String>) {
        if (pathsToDelete.isEmpty()) {
            Logger.logInfo("No cache items selected for deletion.")
            return
        }
        Logger.logInfo("Deleting ${pathsToDelete.size} selected cache item(s)...")
        var successCount = 0
        var spaceFreed = 0L
        val parentDirs = pathsToDelete.mapNotNull { File(it).parent }.toSet()

        pathsToDelete.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                val sizeOfFile = file.walk().sumOf { it.length() }
                if (file.deleteRecursively()) {
                    Logger.logDebug { "Deleted: $path" }
                    successCount++
                    spaceFreed += sizeOfFile
                } else {
                    Logger.logError("Failed to delete: $path")
                }
            }
        }

        // Clean up parent directories if they are now empty of chapters
        parentDirs.forEach { dirPath ->
            val dir = File(dirPath)
            // A series directory should be removed if it's either completely empty
            // or if it no longer contains any chapter subdirectories.
            if (dir.exists() && dir.isDirectory) {
                val remainingEntries = dir.listFiles()
                if (remainingEntries.isNullOrEmpty() || remainingEntries.none { it.isDirectory }) {
                    Logger.logDebug { "Parent directory contains no more chapters. Deleting recursively: ${dir.name}" }
                    dir.deleteRecursively()
                }
            }
        }

        Logger.logInfo("Successfully deleted $successCount item(s), freeing up ${formatSize(spaceFreed)} of space.")
    }

    fun clearAllAppCache() {
        Logger.logInfo("Clearing all application cache...")
        val tempDir = File(platformProvider.getTmpDir())
        if (!tempDir.exists() || !tempDir.isDirectory) {
            Logger.logInfo("Cache directory not found.")
            return
        }

        val seriesDirs = tempDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("manga-dl-")
        }

        if (seriesDirs.isNullOrEmpty()) {
            Logger.logInfo("No cache directories found to clear.")
            return
        }

        var allSucceeded = true
        var spaceFreed = 0L
        seriesDirs.forEach { dir ->
            val sizeOfDir = dir.walk().sumOf { it.length() }
            if (dir.deleteRecursively()) {
                Logger.logDebug { "Deleted: ${dir.path}" }
                spaceFreed += sizeOfDir
            } else {
                Logger.logError("Failed to delete cache directory: ${dir.path}")
                allSucceeded = false
            }
        }

        if (allSucceeded) {
            Logger.logInfo("Successfully cleared all application cache, freeing up ${formatSize(spaceFreed)}.")
        } else {
            Logger.logError("Failed to clear some cache directories.")
        }
    }
}
