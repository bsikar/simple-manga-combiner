package com.mangacombiner.util

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File

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
        logError("Error reading CBZ ${cbzFile.name} to infer chapter slugs", e)
        emptySet()
    }
}

fun inferChapterSlugsFromEpub(epubFile: File): Set<String> {
    if (!epubFile.exists() || !epubFile.isFile || !epubFile.canRead()) return emptySet()
    return try {
        ZipFile(epubFile).use { zipFile ->
            val slugRegex = Regex("""^(.*?)_page_\d+\..*$""")
            zipFile.fileHeaders
                .asSequence()
                .filter { !it.isDirectory && it.fileName.contains("images/") }
                .mapNotNull { slugRegex.find(it.fileName.substringAfterLast('/'))?.groupValues?.get(1) }
                .toSet()
        }
    } catch (e: ZipException) {
        logError("Error reading EPUB ${epubFile.name} to infer chapter slugs", e)
        emptySet()
    }
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
        logError("Error reading ${zipFile.name} for page counts", e)
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
        val slugRegex = Regex("""^(.*?)_page_\d+\..*$""")
        paths
            .filter { it.contains("images/") }
            .mapNotNull { slugRegex.find(it.substringAfterLast('/'))?.groupValues?.get(1) }
            .groupingBy { it }
            .eachCount()
    }
}
