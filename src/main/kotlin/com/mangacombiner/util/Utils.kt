package com.mangacombiner.util

import com.mangacombiner.model.MangaChapter
import com.mangacombiner.model.MangaType
import java.io.File
import java.io.IOException
import java.nio.file.*

var isDebugEnabled = false

/**
 * Prints a message only if debug mode is enabled.
 * The message is passed as a lambda to avoid constructing the string unless needed.
 */
fun logDebug(message: () -> String) {
    if (isDebugEnabled) {
        println("[DEBUG] ${message()}")
    }
}

fun getOutputPath(title: String, outputDir: String?): Path {
    logDebug { "getOutputPath: Generating output path for title: '$title' in directory: '$outputDir'" }
    val safeTitle = title.replace(Regex("[^a-zA-Z0-9_\\-\\(\\)]"), "_").replace(" ", "_")
    logDebug { "getOutputPath: Sanitized title: '$safeTitle'" }
    val outputFileName = "${safeTitle}_Combined.cbz"
    val finalPath = outputDir?.let { Paths.get(it, outputFileName) } ?: Paths.get(outputFileName)
    logDebug { "getOutputPath: Final generated path: '${finalPath.toAbsolutePath()}'" }
    return finalPath
}

fun checkForMissingChapters(title: String, chapters: List<MangaChapter>) {
    println("[$title] Checking for missing chapters...")
    logDebug { "checkForMissingChapters: Checking ${chapters.size} chapters for title '$title'."}
    var warnings = 0
    if (chapters.size > 1) {
        for (i in 0 until chapters.size - 1) {
            val current = chapters[i]
            val next = chapters[i + 1]
            logDebug { "checkForMissingChapters: Comparing '${current.file.name}' (Ch ${current.chapter}) with '${next.file.name}' (Ch ${next.chapter})." }
            if (current.type == MangaType.CHAPTER && next.type == MangaType.CHAPTER) {
                val expectedNextChapter = current.chapter + 1
                if (next.chapter > expectedNextChapter) {
                    println("[$title] Warning: Possible missing chapter(s) between ${current.file.name} and ${next.file.name}. (Gap between Ch. ${current.chapter.toInt()} and ${next.chapter.toInt()})")
                    warnings++
                }
            }
        }
    }
    if (warnings == 0) {
        println("[$title] No obvious gaps found based on filenames.")
        logDebug { "checkForMissingChapters: No gaps detected." }
    }
}


/**
 * Expands a path that may contain a glob pattern (like *) into a list of directories.
 */
fun expandGlobPath(pathWithGlob: String): List<File> {
    logDebug { "expandGlobPath: Received path '$pathWithGlob'" }

    // If there are no wildcards, just check if the path is a valid directory.
    if (!pathWithGlob.contains('*') && !pathWithGlob.contains('?') && !pathWithGlob.contains('[')) {
        val singleFile = File(pathWithGlob)
        return if (singleFile.isDirectory) {
            logDebug { "expandGlobPath: Path has no wildcards and is a valid directory." }
            listOf(singleFile)
        } else {
            logDebug { "expandGlobPath: Path has no wildcards and is not a valid directory." }
            emptyList()
        }
    }

    // Separate the path into the base directory (for searching) and the glob pattern.
    val lastSeparatorIndex = pathWithGlob.lastIndexOf(File.separator)
    val baseDirStr = if (lastSeparatorIndex > -1) pathWithGlob.substring(0, lastSeparatorIndex) else "."
    val glob = pathWithGlob.substring(lastSeparatorIndex + 1)

    val basePath = Paths.get(baseDirStr)
    if (!Files.isDirectory(basePath)) {
        logDebug { "expandGlobPath: Base directory '$basePath' for globbing does not exist." }
        return emptyList()
    }

    logDebug { "expandGlobPath: Searching in base '${basePath.toAbsolutePath()}' with glob pattern '$glob'" }

    val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
    val matchedDirs = mutableListOf<File>()

    // Use a DirectoryStream to find all items in the base path that match the glob.
    try {
        Files.newDirectoryStream(basePath).use { stream ->
            for (path in stream) {
                if (matcher.matches(path.fileName) && Files.isDirectory(path)) {
                    logDebug { "expandGlobPath:  -> Matched directory: ${path.fileName}" }
                    matchedDirs.add(path.toFile())
                }
            }
        }
    } catch (e: IOException) {
        logDebug { "expandGlobPath: Error while searching directory: ${e.message}" }
        return emptyList()
    }


    logDebug { "expandGlobPath: Found ${matchedDirs.size} matching directories." }
    return matchedDirs.sorted()
}
