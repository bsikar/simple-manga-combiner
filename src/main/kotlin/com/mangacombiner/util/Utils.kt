package com.mangacombiner.util

import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths

var isDebugEnabled = false

fun logDebug(message: () -> String) {
    if (isDebugEnabled) {
        println("[DEBUG] ${message()}")
    }
}

fun inferChapterSlugsFromZip(cbzFile: File): Set<String> {
    if (!cbzFile.exists() || !cbzFile.isFile) return emptySet()
    return try {
        ZipFile(cbzFile).use { zipFile ->
            zipFile.fileHeaders
                .filter { it.isDirectory }
                .map { it.fileName.trimEnd('/') }
                .toSet()
        }
    } catch (e: Exception) {
        logDebug { "Could not read zip file to infer slugs: ${cbzFile.name}. Reason: ${e.message}" }
        emptySet()
    }
}

fun expandGlobPath(pathWithGlob: String): List<File> {
    if ('*' !in pathWithGlob && '?' !in pathWithGlob) {
        val file = File(pathWithGlob)
        return if (file.exists()) listOf(file) else emptyList()
    }

    val lastSeparatorIndex = pathWithGlob.lastIndexOf(File.separator)
    val baseDirStr = if (lastSeparatorIndex > -1) pathWithGlob.substring(0, lastSeparatorIndex) else "."
    val glob = pathWithGlob.substring(lastSeparatorIndex + 1)
    val basePath = Paths.get(baseDirStr)

    if (!Files.isDirectory(basePath)) return emptyList()

    val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
    val matchedFiles = mutableListOf<File>()

    try {
        Files.newDirectoryStream(basePath).use { stream ->
            stream.forEach { path ->
                if (matcher.matches(path.fileName)) {
                    matchedFiles.add(path.toFile())
                }
            }
        }
    } catch (e: IOException) {
        logDebug { "Error while expanding glob path: ${e.message}" }
        return emptyList()
    }
    return matchedFiles.sorted()
}
