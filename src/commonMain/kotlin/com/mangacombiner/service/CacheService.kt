package com.mangacombiner.service

import com.mangacombiner.util.CachedChapterNameComparator
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.formatSize
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
    val totalSizeInBytes: Long,
    val plannedChapterCount: Int?
)

class CacheService(
    private val platformProvider: PlatformProvider,
    private val persistenceService: QueuePersistenceService
) {

    fun getCacheSize(): Long {
        val tempDir = File(platformProvider.getTmpDir())
        if (!tempDir.exists() || !tempDir.isDirectory) return 0L

        val seriesDirs = tempDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("manga-dl-")
        }
        return seriesDirs?.sumOf { it.walk().sumOf { file -> file.length() } } ?: 0L
    }

    fun getCacheContents(): List<CachedSeries> {
        val temp = File(platformProvider.getTmpDir())
        if (!temp.exists() || !temp.isDirectory) {
            return emptyList()
        }

        return temp.listFiles(FileFilter { file ->
            file.isDirectory && file.name.startsWith("manga-dl-")
        })?.map { seriesDir ->
            val chapters = seriesDir.listFiles(FileFilter { it.isDirectory })?.mapNotNull { chapterDir ->
                val files = chapterDir.walk().filter { it.isFile }.toList()
                if (files.any { it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS }) {
                    CachedChapter(
                        name = chapterDir.name,
                        path = chapterDir.absolutePath,
                        sizeInBytes = files.sumOf { it.length() },
                        sizeFormatted = formatSize(files.sumOf { it.length() }),
                        pageCount = files.count { it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS },
                        parentPath = seriesDir.absolutePath,
                        isBroken = File(chapterDir, ".incomplete").exists()
                    )
                } else null
            }?.sortedWith(CachedChapterNameComparator) ?: emptyList()

            val urlFile = File(seriesDir, "url.txt")
            val metadata = persistenceService.loadOperationMetadata(seriesDir.absolutePath)
            val seriesUrl = metadata?.seriesUrl ?: if (urlFile.exists()) urlFile.readText().trim() else null
            val totalSize = seriesDir.walk().sumOf { it.length() }

            CachedSeries(
                seriesName = metadata?.customTitle ?: seriesDir.name.removePrefix("manga-dl-").replace('-', ' ').titlecase(),
                path = seriesDir.absolutePath,
                seriesUrl = seriesUrl,
                chapters = chapters,
                totalSizeInBytes = totalSize,
                totalSizeFormatted = formatSize(totalSize),
                plannedChapterCount = metadata?.chapters?.size
            )
        }?.sortedBy { it.seriesName } ?: emptyList()
    }

    fun getCachedChapterStatus(seriesSlug: String): Map<String, Boolean> {
        val seriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")
        if (!seriesDir.exists() || !seriesDir.isDirectory) {
            return emptyMap()
        }
        return seriesDir.listFiles(FileFilter { it.isDirectory })
            ?.associate { chapterDir ->
                chapterDir.name to !File(chapterDir, ".incomplete").exists()
            } ?: emptyMap()
    }

    fun deleteCacheItems(pathsToDelete: List<String>) {
        if (pathsToDelete.isEmpty()) {
            Logger.logInfo("No cache items selected for deletion.")
            return
        }
        var successCount = 0
        var spaceFreed = 0L

        pathsToDelete.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                val sizeOfFile = file.walk().sumOf { it.length() }
                if (file.deleteRecursively()) {
                    successCount++
                    spaceFreed += sizeOfFile
                } else {
                    Logger.logError("Failed to delete: $path")
                }
            }
        }
        Logger.logInfo("Successfully deleted $successCount item(s), freeing up ${formatSize(spaceFreed)} of space.")
    }

    fun clearAllAppCache() {
        val allPaths = getCacheContents().map { it.path }
        if (allPaths.isEmpty()) {
            Logger.logInfo("Cache is already empty.")
            return
        }
        deleteCacheItems(allPaths)
    }
}
