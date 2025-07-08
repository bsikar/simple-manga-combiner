package com.mangacombiner.util

import com.mangacombiner.model.ComicInfo
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
                    .filter { !it.isDirectory && it.fileName.lowercase() != "comicinfo.xml" }
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

    /**
     * Extracts and returns the content of the <Web> tag from ComicInfo.xml in a CBZ file.
     */
    fun getSourceUrlFromCbz(cbzFile: File, xml: XML): String? {
        if (!cbzFile.exists()) return null
        return try {
            ZipFile(cbzFile).use { zipFile ->
                val comicInfoFile = zipFile.getFileHeader("ComicInfo.xml")
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

    /**
     * Extracts and returns the content of the <dc:source> tag from content.opf in an EPUB file.
     */
    fun getSourceUrlFromEpub(epubFile: File): String? {
        if (!epubFile.exists()) return null
        return try {
            ZipFile(epubFile).use { zipFile ->
                val opfFileHeader = zipFile.fileHeaders.find { it.fileName.endsWith("content.opf") }
                if (opfFileHeader != null) {
                    zipFile.getInputStream(opfFileHeader).use {
                        val content = it.reader().readText()
                        // Simple regex is more robust than full XML parsing for this one tag
                        Regex("""<dc:source>(.*?)</dc:source>""").find(content)?.groupValues?.get(1)
                    }
                } else null
            }
        } catch (e: Exception) {
            Logger.logError("Could not read content.opf from ${epubFile.name}", e)
            null
        }
    }

    /**
     * Reconstructs a directory of chapter folders from an extracted EPUB's content.
     * This is necessary because EPUBs don't have a standard chapter-folder structure.
     * It relies on the image naming convention used by this application.
     */
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

    private fun getPageCountsFromZip(
        zipFile: File,
        countExtractor: (Sequence<String>) -> Map<String, Int>
    ): Map<String, Int> {
        if (!zipFile.exists() || !zipFile.isFile || !zipFile.canRead()) return emptyMap()
        return try {
            ZipFile(zipFile).use { file ->
                val paths = file.fileHeaders
                    .asSequence()
                    .filter { !it.isDirectory && it.fileName.lowercase() != "comicinfo.xml" }
                    .map { it.fileName }
                countExtractor(paths)
            }
        } catch (e: ZipException) {
            Logger.logError("Error reading ${zipFile.name} for page counts", e)
            emptyMap()
        }
    }

    fun getChapterPageCountsFromZip(cbzFile: File): Map<String, Int> {
        return getPageCountsFromZip(cbzFile) { paths ->
            paths
                .mapNotNull { File(it).parent }
                .groupingBy { it }
                .eachCount()
        }
    }

    fun getChapterPageCountsFromEpub(epubFile: File): Map<String, Int> {
        return getPageCountsFromZip(epubFile) { paths ->
            val slugRegex = Regex("""^img_(.+)_\d+\..*""")
            paths
                .filter { it.contains("images/") }
                .mapNotNull { slugRegex.find(it.substringAfterLast('/'))?.groupValues?.get(1) }
                .groupingBy { it }
                .eachCount()
        }
    }

    /**
     * Infers a chapter slug from a single image file path within an EPUB.
     */
    private fun inferChapterSlugsFromEpubPath(path: String): String? {
        val slugRegex = Regex("""^img_(.+)_\d+\..*""")
        return slugRegex.find(path.substringAfterLast('/'))?.groupValues?.get(1)
    }
}
