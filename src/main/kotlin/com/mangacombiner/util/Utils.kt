package com.mangacombiner.util

import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.PatternSyntaxException

private val debugEnabled = AtomicBoolean(false)

var isDebugEnabled: Boolean
    get() = debugEnabled.get()
    set(value) = debugEnabled.set(value)

inline fun logDebug(message: () -> String) {
    if (isDebugEnabled) {
        println("[DEBUG] ${message()}")
    }
}

fun logError(message: String, throwable: Throwable? = null) {
    println("[ERROR] $message")
    if (isDebugEnabled && throwable != null) {
        println("[ERROR] Stack trace: ${throwable.stackTraceToString()}")
    }
}

private val numberRegex = "\\d+".toRegex()

fun parseChapterSlugsForSorting(slug: String): List<Int> {
    return numberRegex.findAll(slug).mapNotNull { it.value.toIntOrNull() }.toList()
}

fun normalizeChapterSlug(slug: String): String {
    val numbers = numberRegex.findAll(slug).map { it.value }.toList()
    if (numbers.isEmpty()) return slug.replace(Regex("[_\\-]"), ".")

    var tempSlug = slug
    numbers.firstOrNull()?.let { firstNum ->
        val paddedFirstNumber = firstNum.padStart(4, '0')
        tempSlug = tempSlug.replaceFirst(firstNum, paddedFirstNumber)
    }

    return tempSlug.replace(Regex("[_\\-]"), ".")
}

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
    } catch (e: Exception) {
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
    } catch (e: Exception) {
        logError("Error reading EPUB ${epubFile.name} to infer chapter slugs", e)
        emptySet()
    }
}

fun getChapterPageCountsFromZip(cbzFile: File): Map<String, Int> {
    if (!cbzFile.exists() || !cbzFile.isFile || !cbzFile.canRead()) return emptyMap()
    return try {
        ZipFile(cbzFile).use { zipFile ->
            zipFile.fileHeaders
                .asSequence()
                .filter { !it.isDirectory && it.fileName.lowercase() != "comicinfo.xml" }
                .mapNotNull { File(it.fileName).parent?.let { parent -> parent to it } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.size }
        }
    } catch (e: Exception) {
        logError("Error reading CBZ ${cbzFile.name} for page counts", e)
        emptyMap()
    }
}

fun getChapterPageCountsFromEpub(epubFile: File): Map<String, Int> {
    if (!epubFile.exists() || !epubFile.isFile || !epubFile.canRead()) return emptyMap()
    return try {
        ZipFile(epubFile).use { zipFile ->
            val slugRegex = Regex("""^(.*?)_page_\d+\..*$""")
            zipFile.fileHeaders
                .asSequence()
                .filter { !it.isDirectory && it.fileName.contains("images/") }
                .mapNotNull { slugRegex.find(it.fileName.substringAfterLast('/'))?.groupValues?.get(1) }
                .groupingBy { it }
                .eachCount()
        }
    } catch (e: Exception) {
        logError("Error reading EPUB ${epubFile.name} for page counts", e)
        emptyMap()
    }
}

fun expandGlobPath(pathWithGlob: String): List<File> {
    if ('*' !in pathWithGlob && '?' !in pathWithGlob && '[' !in pathWithGlob && '{' !in pathWithGlob) {
        val file = File(pathWithGlob)
        return if (file.exists()) listOf(file) else emptyList()
    }

    val path = Paths.get(pathWithGlob)
    val parent = path.parent
    val filePattern = path.fileName?.toString() ?: return emptyList()

    val baseDir = when {
        parent != null -> parent
        pathWithGlob.startsWith(File.separator) -> Paths.get(File.separator)
        else -> Paths.get(".")
    }

    if (!Files.isDirectory(baseDir)) {
        logDebug { "Base directory does not exist: $baseDir" }
        return emptyList()
    }

    val matcher = try {
        FileSystems.getDefault().getPathMatcher("glob:$filePattern")
    } catch (e: PatternSyntaxException) {
        logDebug { "Invalid glob pattern: $filePattern" }
        return emptyList()
    }

    val matchedFiles = mutableListOf<File>()
    try {
        Files.walkFileTree(baseDir, setOf(FileVisitOption.FOLLOW_LINKS), 1, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (matcher.matches(file.fileName)) {
                    matchedFiles.add(file.toFile())
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                logDebug { "Failed to access file: $file" }
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: IOException) {
        logError("Error while expanding glob path", e)
    }
    return matchedFiles.sorted()
}

fun sanitizeFilename(filename: String): String {
    val invalidChars = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')
    val sanitized = filename
        .map { char -> if (char in invalidChars) '_' else char }
        .joinToString("")
        .trim()
        .take(255)

    return when {
        sanitized.isEmpty() -> "unnamed"
        sanitized.all { it == '.' } -> "unnamed"
        else -> sanitized
    }
}
