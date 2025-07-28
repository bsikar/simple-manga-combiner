package com.mangacombiner.service

import com.mangacombiner.util.Logger
import net.lingala.zip4j.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI

actual class EpubReaderService {
    actual suspend fun parseEpub(filePath: String): Book? {
        val epubFile = File(filePath)
        if (!epubFile.exists()) return null

        try {
            ZipFile(epubFile).use { zip ->
                val containerEntry = zip.getFileHeader("META-INF/container.xml") ?: return null
                val opfPath = zip.getInputStream(containerEntry).use {
                    val doc = Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
                    doc.selectFirst("rootfile")?.attr("full-path")
                } ?: return null

                val opfEntry = zip.getFileHeader(opfPath) ?: return null
                val opfDoc = zip.getInputStream(opfEntry).use {
                    Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
                }

                val title = opfDoc.selectFirst("metadata > dc|title")?.text() ?: "Unknown Title"
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

                    val chapterImages = chapterDoc.select("img").mapNotNull { img ->
                        val imgHref = img.attr("src")
                        val imgPath = URI(chapterPath).resolve(imgHref).path.removePrefix("/")
                        zip.getFileHeader(imgPath)?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
                    }

                    if (chapterImages.isNotEmpty()) {
                        ChapterContent(chapterTitle, chapterImages)
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
                        val allImages = pages.flatMap { it.imageResources }
                        ChapterContent(title = chapterTitle, imageResources = allImages)
                    }


                return Book(
                    filePath = filePath,
                    title = title,
                    coverImage = coverImageBytes,
                    chapters = groupedChapters
                )
            }
        } catch (e: Exception) {
            Logger.logError("Failed to parse EPUB with zip4j: $filePath", e)
            return null
        }
    }
}
