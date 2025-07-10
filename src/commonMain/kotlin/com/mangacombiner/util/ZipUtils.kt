package com.mangacombiner.util

import com.mangacombiner.model.ComicInfo
import com.mangacombiner.service.ProcessorService
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.serialization.XML
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File
import java.nio.file.Files

object ZipUtils {
    fun inferChapterSlugsFromZip(cbzFile: File): Set<String> {
        if (!cbzFile.exists() || !cbzFile.isFile || !cbzFile.canRead()) return emptySet()
        return try {
            ZipFile(cbzFile).use { zipFile ->
                zipFile.fileHeaders
                    .asSequence()
                    .filter { !it.isDirectory && !it.fileName.lowercase().endsWith(ProcessorService.COMIC_INFO_FILE) && !it.fileName.lowercase().endsWith(ProcessorService.FAILURES_FILE) }
                    .mapNotNull { File(it.fileName).parent }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        } catch (e: ZipException) {
            Logger.logError("Error reading CBZ ${cbzFile.name} to infer chapter slugs", e)
            emptySet()
        }
    }

    fun inferChapterSlugsFromEpub(epubFile: File): Set<String> {
        if (!epubFile.exists() || !epubFile.isFile || !epubFile.canRead()) return emptySet()
        return try {
            ZipFile(epubFile).use { zipFile ->
                zipFile.fileHeaders
                    .asSequence()
                    .filter { !it.isDirectory && it.fileName.contains("images/") }
                    .mapNotNull { inferChapterSlugsFromEpubPath(it.fileName) }
                    .toSet()
            }
        } catch (e: ZipException) {
            Logger.logError("Error reading EPUB ${epubFile.name} to infer chapter slugs", e)
            emptySet()
        }
    }

    fun getSourceUrlFromCbz(cbzFile: File, xml: XML): String? {
        if (!cbzFile.exists()) return null
        return try {
            ZipFile(cbzFile).use { zipFile ->
                val comicInfoFile = zipFile.getFileHeader(ProcessorService.COMIC_INFO_FILE)
                if (comicInfoFile != null) {
                    zipFile.getInputStream(comicInfoFile).use {
                        xml.decodeFromString(ComicInfo.serializer(), it.reader().readText()).web
                    }
                } else null
            }
        } catch (e: Exception) {
            Logger.logError("Could not read ComicInfo.xml from ${cbzFile.name}", e)
            null
        }
    }

    fun getSourceUrlFromEpub(epubFile: File): String? {
        if (!epubFile.exists()) return null
        return try {
            ZipFile(epubFile).use { zipFile ->
                val opfFileHeader = zipFile.fileHeaders.find { it.fileName.endsWith("content.opf") }
                if (opfFileHeader != null) {
                    zipFile.getInputStream(opfFileHeader).use {
                        val content = it.reader().readText()
                        Regex("""<dc:source>(.*?)</dc:source>""").find(content)?.groupValues?.get(1)
                    }
                } else null
            }
        } catch (e: Exception) {
            Logger.logError("Could not read content.opf from ${epubFile.name}", e)
            null
        }
    }

    fun getFailedItems(zipFile: File, json: Json): Map<String, List<String>> {
        if (!zipFile.exists()) return emptyMap()
        return try {
            ZipFile(zipFile).use { file ->
                val failuresHeader = file.fileHeaders.find { it.fileName.endsWith(ProcessorService.FAILURES_FILE) }
                if (failuresHeader != null) {
                    file.getInputStream(failuresHeader).use {
                        json.decodeFromString<Map<String, List<String>>>(it.reader().readText())
                    }
                } else {
                    emptyMap()
                }
            }
        } catch (e: Exception) {
            Logger.logError("Could not read or parse ${ProcessorService.FAILURES_FILE} from ${zipFile.name}", e)
            emptyMap()
        }
    }

    fun reconstructChapterFoldersFromEpub(extractedDir: File, chaptersToKeep: List<String>): List<File> {
        val repackedDir = Files.createTempDirectory("mangacombiner-repack-").toFile()
        val imageDir = File(extractedDir, "OEBPS/images")

        if (!imageDir.exists() || !imageDir.isDirectory) {
            Logger.logError("Could not find OEBPS/images directory in extracted EPUB.")
            return emptyList()
        }

        val chapterSlugMap = chaptersToKeep.associateBy { it }

        val imageFilesByChapter = imageDir.listFiles()
            ?.mapNotNull { file ->
                val slug = inferChapterSlugsFromEpubPath(file.name)
                if (slug != null && slug in chapterSlugMap) slug to file else null
            }
            ?.groupBy({ it.first }, { it.second })

        imageFilesByChapter?.forEach { (slug, files) ->
            val newChapterDir = File(repackedDir, slug).apply { mkdirs() }
            files.sorted().forEach { it.copyTo(File(newChapterDir, it.name)) }
        }

        return repackedDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
    }

    /**
     * Extracts a zip file to a given destination.
     * @return True on success, false on failure.
     */
    fun extractZip(zipFile: File, destination: File): Boolean {
        return try {
            Logger.logDebug { "Extracting ${zipFile.name} to ${destination.absolutePath}" }
            ZipFile(zipFile).extractAll(destination.absolutePath)
            true
        } catch (e: ZipException) {
            Logger.logError("Failed to extract zip file ${zipFile.name}", e)
            false
        }
    }

    private fun inferChapterSlugsFromEpubPath(path: String): String? {
        val slugRegex = Regex("""^img_(.+)_\d+\..*""")
        return slugRegex.find(path.substringAfterLast('/'))?.groupValues?.get(1)
    }
}
