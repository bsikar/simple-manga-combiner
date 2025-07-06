package com.mangacombiner.util

import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.PatternSyntaxException

object FileUtils {
    private const val FILENAME_MAX_LENGTH = 255
    private val GLOB_CHARS = setOf('*', '?', '[', '{')
    private val INVALID_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /**
     * File visitor to find files matching a glob pattern.
     */
    private class GlobFileVisitor(
        private val matcher: PathMatcher,
        private val matchedFiles: MutableList<File>
    ) : SimpleFileVisitor<Path>() {

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (matcher.matches(file.fileName)) {
                matchedFiles.add(file.toFile())
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
            Logger.logError("Failed to access file: $file", exc)
            return FileVisitResult.CONTINUE
        }
    }

    fun expandGlobPath(pathWithGlob: String): List<File> {
        val matchedFiles = mutableListOf<File>()

        if (pathWithGlob.none { it in GLOB_CHARS }) {
            val file = File(pathWithGlob)
            if (file.exists()) {
                matchedFiles.add(file)
            }
        } else {
            val path = Paths.get(pathWithGlob)
            val parent = path.parent ?: Paths.get(".")
            val filePattern = path.fileName?.toString()

            if (filePattern != null && Files.isDirectory(parent)) {
                try {
                    val matcher = FileSystems.getDefault().getPathMatcher("glob:$filePattern")
                    Files.walkFileTree(
                        parent,
                        setOf(FileVisitOption.FOLLOW_LINKS),
                        1,
                        GlobFileVisitor(matcher, matchedFiles)
                    )
                } catch (e: PatternSyntaxException) {
                    Logger.logError("Invalid glob pattern: $filePattern", e)
                } catch (e: IOException) {
                    Logger.logError("Error while expanding glob path", e)
                }
            } else {
                Logger.logDebug { "Base directory does not exist or pattern is invalid: $parent" }
            }
        }
        return matchedFiles.sorted()
    }

    fun sanitizeFilename(filename: String): String {
        val sanitized = filename
            .map { char -> if (char in INVALID_FILENAME_CHARS) '_' else char }
            .joinToString("")
            .trim()
            .take(FILENAME_MAX_LENGTH)

        return when {
            sanitized.isEmpty() -> "unnamed"
            sanitized.all { it == '.' } -> "unnamed"
            else -> sanitized
        }
    }
}
