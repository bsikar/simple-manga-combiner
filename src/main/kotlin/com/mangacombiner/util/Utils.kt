package com.mangacombiner.util

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.PatternSyntaxException
import kotlin.math.log10
import kotlin.math.pow

/**
 * Global debug flag for enabling verbose logging.
 * Thread-safe implementation using AtomicBoolean.
 */
private val debugEnabled = AtomicBoolean(false)

/**
 * Gets the current debug status.
 */
var isDebugEnabled: Boolean
    get() = debugEnabled.get()
    set(value) = debugEnabled.set(value)

/**
 * Logs a debug message if debug mode is enabled.
 * The message is lazily evaluated to avoid unnecessary string construction.
 *
 * @param message Lambda that returns the message to log
 */
inline fun logDebug(message: () -> String) {
    if (isDebugEnabled) {
        println("[DEBUG] ${message()}")
    }
}

/**
 * Logs an info message.
 *
 * @param message The message to log
 */
fun logInfo(message: String) {
    println("[INFO] $message")
}

/**
 * Logs a warning message.
 *
 * @param message The warning message to log
 */
fun logWarning(message: String) {
    println("[WARNING] $message")
}

/**
 * Logs an error message.
 *
 * @param message The error message to log
 * @param throwable Optional exception to log
 */
fun logError(message: String, throwable: Throwable? = null) {
    println("[ERROR] $message")
    if (isDebugEnabled && throwable != null) {
        println("[ERROR] Stack trace: ${throwable.stackTraceToString()}")
    }
}

/**
 * Infers chapter slugs from the directory structure within a CBZ file.
 * This version is robust and handles flat archives by checking the parent
 * directory of each file, similar to the Python implementation.
 *
 * @param cbzFile The CBZ file to analyze
 * @return Set of chapter slugs found in the archive
 */
fun inferChapterSlugsFromZip(cbzFile: File): Set<String> {
    if (!cbzFile.exists() || !cbzFile.isFile || !cbzFile.canRead()) {
        logDebug { "Cannot read file or file does not exist: ${cbzFile.absolutePath}" }
        return emptySet()
    }

    return try {
        ZipFile(cbzFile).use { zipFile ->
            if (!zipFile.isValidZipFile) {
                logDebug { "Invalid ZIP file: ${cbzFile.name}" }
                return emptySet()
            }

            val slugs = zipFile.fileHeaders
                .asSequence()
                .filter { !it.isDirectory && it.fileName.lowercase() != "comicinfo.xml" }
                .mapNotNull { File(it.fileName).parent }
                .filter { it.isNotBlank() }
                .toSet()

            logDebug { "Found ${slugs.size} chapter slugs in ${cbzFile.name}: $slugs" }
            slugs
        }
    } catch (e: ZipException) {
        logError("ZIP format error reading ${cbzFile.name}", e)
        emptySet()
    } catch (e: IOException) {
        logError("IO error reading ${cbzFile.name}", e)
        emptySet()
    } catch (e: Exception) {
        logError("Unexpected error reading ${cbzFile.name}", e)
        emptySet()
    }
}


/**
 * Expands a file path containing glob patterns (wildcards).
 * Supports patterns like "*.cbz", "manga-*.cbz", etc.
 *
 * @param pathWithGlob The path potentially containing glob patterns
 * @return List of matching files, sorted by name
 */
fun expandGlobPath(pathWithGlob: String): List<File> {
    // Check if path contains glob characters
    if ('*' !in pathWithGlob && '?' !in pathWithGlob && '[' !in pathWithGlob && '{' !in pathWithGlob) {
        val file = File(pathWithGlob)
        return if (file.exists()) listOf(file) else emptyList()
    }

    // Parse the base directory and glob pattern
    val path = Paths.get(pathWithGlob)
    val parent = path.parent
    val filePattern = path.fileName?.toString() ?: return emptyList()

    // Determine base directory
    val baseDir = when {
        parent != null -> parent
        pathWithGlob.startsWith(File.separator) -> Paths.get(File.separator)
        else -> Paths.get(".")
    }

    if (!Files.isDirectory(baseDir)) {
        logDebug { "Base directory does not exist: $baseDir" }
        return emptyList()
    }

    // Create glob matcher
    val matcher = try {
        FileSystems.getDefault().getPathMatcher("glob:$filePattern")
    } catch (e: PatternSyntaxException) {
        logDebug { "Invalid glob pattern: $filePattern" }
        return emptyList()
    }

    val matchedFiles = mutableListOf<File>()

    try {
        // Walk directory and find matches
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
        return emptyList()
    }

    logDebug { "Glob pattern '$pathWithGlob' matched ${matchedFiles.size} files" }
    return matchedFiles.sorted()
}

/**
 * Formats a file size in bytes to a human-readable string.
 *
 * @param size The size in bytes
 * @return Formatted string (e.g., "1.5 MB", "842 KB")
 */
fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()

    return DecimalFormat("#,##0.#").format(
        size / 1024.0.pow(digitGroups.toDouble())
    ) + " " + units[digitGroups]
}

/**
 * Safely deletes a file or directory recursively.
 *
 * @param file The file or directory to delete
 * @return true if deletion was successful, false otherwise
 */
fun safeDelete(file: File): Boolean {
    return try {
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    } catch (e: Exception) {
        logError("Failed to delete: ${file.absolutePath}", e)
        false
    }
}

/**
 * Creates a temporary directory with a specific prefix.
 * The directory will be created in the system's temporary directory.
 *
 * @param prefix The prefix for the temporary directory name
 * @return The created temporary directory
 * @throws IOException if directory creation fails
 */
@Throws(IOException::class)
fun createTempDirectory(prefix: String): File {
    val tempDir = Files.createTempDirectory(prefix).toFile()
    logDebug { "Created temporary directory: ${tempDir.absolutePath}" }
    return tempDir
}

/**
 * Ensures a directory exists, creating it if necessary.
 *
 * @param directory The directory to ensure exists
 * @return true if the directory exists or was created, false otherwise
 */
fun ensureDirectory(directory: File): Boolean {
    return when {
        directory.exists() && directory.isDirectory -> true
        directory.exists() && !directory.isDirectory -> {
            logError("Path exists but is not a directory: ${directory.absolutePath}")
            false
        }
        else -> {
            try {
                val created = directory.mkdirs()
                if (created) {
                    logDebug { "Created directory: ${directory.absolutePath}" }
                }
                created
            } catch (e: SecurityException) {
                logError("Permission denied creating directory: ${directory.absolutePath}", e)
                false
            }
        }
    }
}

/**
 * Gets the file extension in lowercase, or empty string if none.
 *
 * @return The file extension without the dot, in lowercase
 */
fun File.extensionLowerCase(): String {
    return extension.lowercase()
}

/**
 * Checks if a file has any of the specified extensions.
 *
 * @param extensions The extensions to check (without dots)
 * @return true if the file has one of the extensions
 */
fun File.hasExtension(vararg extensions: String): Boolean {
    val fileExt = extensionLowerCase()
    return extensions.any { it.lowercase() == fileExt }
}

/**
 * Gets the total size of a directory and all its contents.
 *
 * @param directory The directory to measure
 * @return Total size in bytes
 */
fun getDirectorySize(directory: File): Long {
    if (!directory.exists() || !directory.isDirectory) {
        return 0L
    }

    var size = 0L

    try {
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                size += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                logDebug { "Failed to access file during size calculation: $file" }
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: IOException) {
        logDebug { "Error calculating directory size: ${e.message}" }
    }

    return size
}

/**
 * Extension property to get a File's size in a human-readable format.
 */
val File.formattedSize: String
    get() = formatFileSize(if (isDirectory) getDirectorySize(this) else length())

/**
 * Sanitizes a filename by removing or replacing invalid characters.
 *
 * @param filename The filename to sanitize
 * @return Sanitized filename safe for use on most filesystems
 */
fun sanitizeFilename(filename: String): String {
    // Characters that are invalid in filenames on various systems
    val invalidChars = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')
    val sanitized = filename
        .map { char -> if (char in invalidChars) '_' else char }
        .joinToString("")
        .trim()
        .take(255) // Maximum filename length on most systems

    // Ensure filename is not empty and doesn't consist only of dots
    return when {
        sanitized.isEmpty() -> "unnamed"
        sanitized.all { it == '.' } -> "unnamed"
        else -> sanitized
    }
}
