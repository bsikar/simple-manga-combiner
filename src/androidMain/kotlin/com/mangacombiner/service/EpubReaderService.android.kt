package com.mangacombiner.service

import android.content.Context
import android.net.Uri
import com.mangacombiner.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.InputStream
import java.net.URI

actual class EpubReaderService : KoinComponent {
    private val context: Context by inject()

    // --- State Management for the currently open book ---
    private var activeZipFile: ZipFile? = null
    private var activeBookCachePath: String? = null

    private suspend fun getInputStream(filePath: String): InputStream? {
        return if (filePath.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(filePath))
        } else {
            File(filePath).inputStream()
        }
    }

    /**
     * Prepares a book for reading by ensuring it's cached and opening a persistent file handle.
     * Call this when the user enters the reader view.
     */
    suspend fun openBookForReading(book: Book) = withContext(Dispatchers.IO) {
        val cachePath = book.localCachePath ?: getCacheFileForBook(book.filePath).absolutePath

        if (activeBookCachePath == cachePath && activeZipFile != null) {
            return@withContext // Book is already open
        }

        closeBookForReading() // Close any previously open book

        val cacheFile = File(cachePath)
        if (!cacheFile.exists()) {
            // This is a safety net; parseEpub should handle the initial caching.
            val inputStream = getInputStream(book.filePath) ?: return@withContext
            inputStream.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        activeZipFile = ZipFile(cacheFile)
        activeBookCachePath = cachePath
        Logger.logDebug { "Opened EPUB file handle for: ${book.title}" }
    }

    /**
     * Closes the file handle for the currently active book.
     * Call this when the user leaves the reader view.
     */
    fun closeBookForReading() {
        try {
            activeZipFile?.close()
            Logger.logDebug { "Closed EPUB file handle for book: $activeBookCachePath" }
        } catch (e: Exception) {
            Logger.logError("Error closing active ZipFile", e)
        } finally {
            activeZipFile = null
            activeBookCachePath = null
        }
    }

    private fun getCacheFileForBook(filePath: String): File {
        return File(context.cacheDir, "epub_cache_${filePath.hashCode()}.zip")
    }

    actual suspend fun parseEpub(filePath: String): Book? = withContext(Dispatchers.IO) {
        val tempEpubFile = getCacheFileForBook(filePath)

        if (!tempEpubFile.exists()) {
            Logger.logDebug { "Caching EPUB for first-time parse: $filePath" }
            val inputStream = getInputStream(filePath) ?: return@withContext null
            inputStream.use { input ->
                tempEpubFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        try {
            ZipFile(tempEpubFile).use { zip ->
                val containerEntry =
                    zip.getFileHeader("META-INF/container.xml") ?: return@withContext null
                val opfPath = zip.getInputStream(containerEntry).use {
                    val doc = Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
                    doc.selectFirst("rootfile")?.attr("full-path")
                } ?: return@withContext null

                val opfEntry = zip.getFileHeader(opfPath) ?: return@withContext null
                val opfDoc = zip.getInputStream(opfEntry).use {
                    Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
                }

                val title = opfDoc.selectFirst("metadata > dc|title")?.text() ?: "Unknown Title"
                val genres = opfDoc.select("metadata > dc|subject").map { it.text() }
                val manifest =
                    opfDoc.select("manifest > item").associate { it.id() to it.attr("href") }
                val coverId = opfDoc.selectFirst("metadata > meta[name=cover]")?.attr("content")
                val coverHref = coverId?.let { manifest[it] }
                val coverImageBytes = coverHref?.let { href ->
                    val coverPath = URI(opfPath).resolve(href).path.removePrefix("/")
                    zip.getFileHeader(coverPath)
                        ?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
                }

                val chapterIds = opfDoc.select("spine > itemref").map { it.attr("idref") }
                val pageLevelChapters = chapterIds.mapNotNull { id ->
                    val chapterHref = manifest[id] ?: return@mapNotNull null
                    val chapterPath = URI(opfPath).resolve(chapterHref).path.removePrefix("/")
                    val chapterEntry = zip.getFileHeader(chapterPath) ?: return@mapNotNull null

                    val chapterContent =
                        zip.getInputStream(chapterEntry).use { it.reader().readText() }
                    val chapterDoc = Jsoup.parse(chapterContent)
                    val chapterTitle = chapterDoc.title()

                    val imageHrefs = chapterDoc.select("img").map { img ->
                        val relativePath = img.attr("src")
                        URI(chapterPath).resolve(relativePath).path.removePrefix("/")
                    }
                    val textContent = if (imageHrefs.isEmpty()) chapterDoc.body()?.text() else null

                    if (imageHrefs.isNotEmpty() || !textContent.isNullOrBlank()) {
                        ChapterContent(chapterTitle, imageHrefs, textContent)
                    } else {
                        null
                    }
                }

                val chapterRegex = Regex("""^(.*?)(?: - Page \d+|$)""")
                val groupedChapters = pageLevelChapters
                    .groupBy { chapter ->
                        chapterRegex.find(chapter.title)?.groupValues?.get(1)?.trim()
                            ?: chapter.title
                    }
                    .map { (chapterTitle, pages) ->
                        val allImageHrefs = pages.flatMap { it.imageHrefs }
                        val combinedText = pages.mapNotNull { it.textContent }.joinToString("\n\n")
                        ChapterContent(
                            title = chapterTitle,
                            imageHrefs = allImageHrefs,
                            textContent = if (combinedText.isNotBlank()) combinedText else null
                        )
                    }

                Book(
                    filePath = filePath,
                    title = title,
                    coverImage = coverImageBytes,
                    chapters = groupedChapters,
                    localCachePath = tempEpubFile.absolutePath,
                    genres = genres.takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            Logger.logError("Failed to parse EPUB: $filePath", e)
            tempEpubFile.delete()
            null
        }
    }

    actual suspend fun extractImage(filePath: String, imageHref: String): ByteArray? =
        withContext(Dispatchers.IO) {
            // The filePath argument is now the expected local cache path.
            // We verify that this is the book we have actively open.
            if (filePath != activeBookCachePath) {
                Logger.logError("Attempted to extract an image from a book that is not the active one. Expected: '$activeBookCachePath', Got: '$filePath'")
                return@withContext null
            }

            try {
                activeZipFile?.let { zip ->
                    zip.getFileHeader(imageHref)?.let {
                        zip.getInputStream(it).use { stream -> stream.readBytes() }
                    }
                }
            } catch (e: Exception) {
                Logger.logError("Failed to extract image '$imageHref' from '$filePath'", e)
                null
            }
        }
}
