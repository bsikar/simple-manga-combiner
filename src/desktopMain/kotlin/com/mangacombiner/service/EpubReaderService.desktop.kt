package com.mangacombiner.service

import com.mangacombiner.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI

actual class EpubReaderService {
    actual suspend fun parseEpub(filePath: String): Book? = withContext(Dispatchers.IO) {
        val epubFile = File(filePath)
        if (!epubFile.exists()) return@withContext null

        try {
            ZipFile(epubFile).use { zip ->
                val containerEntry = zip.getFileHeader("META-INF/container.xml") ?: return@withContext null
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
                val manifest = opfDoc.select("manifest > item").associate { it.id() to it.attr("href") }
                val coverId = opfDoc.selectFirst("metadata > meta[name=cover]")?.attr("content")
                val coverHref = coverId?.let { manifest[it] }
                val coverImageBytes = coverHref?.let { href ->
                    val coverPath = URI(opfPath).resolve(href).path.removePrefix("/")
                    zip.getFileHeader(coverPath)?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
                }

                val chapterIds = opfDoc.select("spine > itemref").map { it.attr("idref") }
                val pageLevelChapters = chapterIds.mapNotNull { id ->
                    val chapterHref = manifest[id] ?: return@mapNotNull null
                    val chapterPath = URI(opfPath).resolve(chapterHref).path.removePrefix("/")
                    val chapterEntry = zip.getFileHeader(chapterPath) ?: return@mapNotNull null

                    val chapterContent = zip.getInputStream(chapterEntry).use { it.reader().readText() }
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

                // Group page-level entries into logical chapters
                val chapterRegex = Regex("""^(.*?)(?: - Page \d+|$)""")
                val groupedChapters = pageLevelChapters
                    .groupBy { chapter ->
                        chapterRegex.find(chapter.title)?.groupValues?.get(1)?.trim() ?: chapter.title
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
                    genres = genres.takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            Logger.logError("Failed to parse EPUB with zip4j: $filePath", e)
            null
        }
    }

    actual suspend fun extractImage(filePath: String, imageHref: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // The imageHref is now the full, resolved path within the zip. No more parsing is needed.
            ZipFile(filePath).use { zip ->
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
