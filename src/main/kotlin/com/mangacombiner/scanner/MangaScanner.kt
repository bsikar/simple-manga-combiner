package com.mangacombiner.scanner

import com.mangacombiner.model.MangaChapter
import com.mangacombiner.model.MangaType
import com.mangacombiner.util.logDebug
import java.io.File

fun scanForManga(directory: File): Map<String, List<MangaChapter>> {
    logDebug { "scanForManga: Starting Manga Scan in: ${directory.path}" }
    val supportedExtensions = listOf("cbz", "cbr", "epub")
    val parsers = listOf(
        fun(file: File): MangaChapter? { // Parser 1
            val regex = """^(.*?)[,\s_-]+v(\d+)\s*c(\d+(\.\d)?).*?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(file.name)?.let {
                val title = it.groupValues[1].trim().replace("_", " ")
                MangaChapter(file, title, MangaType.CHAPTER, it.groupValues[2].toInt(), it.groupValues[3].toDouble())
            }
        },
        fun(file: File): MangaChapter? { // Parser 2 - FIXED
            // This regex now robustly handles any characters between the volume number and the extension.
            val regex = """^(.*?)[,\s_-]+v(\d+).*?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            // This check prevents this parser from matching chapter-based files like "... v01c01.cbz"
            if (file.name.contains(Regex("""c\d""", RegexOption.IGNORE_CASE))) return null
            return regex.find(file.name)?.let {
                val title = it.groupValues[1].trim().replace("_", " ")
                MangaChapter(file, title, MangaType.VOLUME, it.groupValues[2].toInt(), 0.0)
            }
        },
        fun(file: File): MangaChapter? { // Parser 3
            // This regex is now more flexible to handle extra text after the chapter number.
            val regex = """^(.*?)[,\s_-]+c?(\d{1,4}(\.\d)?).*?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(file.name)?.let {
                val title = it.groupValues[1].trim().replace("_", " ")
                if (title.equals("Volume", ignoreCase = true)) return@let null
                MangaChapter(file, title, MangaType.CHAPTER, 0, it.groupValues[2].toDouble())
            }
        },
        fun(file: File): MangaChapter? { // Parser 4
            val regex = """^Volume\s*(\d+).*?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(file.name)?.let {
                val title = file.parentFile.name.replace(Regex("""\s*Volumes.*$""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s*\(\d{4}.*"""), "").trim()
                MangaChapter(file, title, MangaType.VOLUME, it.groupValues[1].toInt(), 0.0)
            }
        },
        fun(file: File): MangaChapter? { // Parser 5 (Fallback)
            val regex = """^(.*?)\s*(?:\(.*?\)|\[.*?\])*\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(file.name)?.let {
                val title = it.groupValues[1].trim().replace("_", " ").removeSuffix(" - The Complete Manga Collection").trim()
                MangaChapter(file, title, MangaType.VOLUME, 1, 0.0)
            }
        }
    )

    logDebug { "scanForManga: Walking file tree..." }
    val foundFiles = directory.walkTopDown().toList()
    logDebug { "scanForManga: Found ${foundFiles.size} total entries in directory tree." }

    val filteredFiles = foundFiles.filter { it.isFile && it.extension.lowercase() in supportedExtensions && !it.name.startsWith("._") }
    if (filteredFiles.isEmpty()) {
        logDebug { "scanForManga: CRITICAL: After filtering, no files matched criteria (is a file, supported extension, not '._')." }
    } else {
        logDebug { "scanForManga: After filtering, found ${filteredFiles.size} potential manga file(s):" }
        filteredFiles.forEach { logDebug { "  - ${it.name}" } }
    }

    val allChapters = filteredFiles.mapNotNull { file ->
        logDebug { "\nscanForManga: >>> Processing file: ${file.name}" }
        var matchedChapter: MangaChapter? = null
        for ((index, parser) in parsers.withIndex()) {
            val result = parser(file)
            if (result != null) {
                logDebug { "  --> MATCHED with Parser #${index + 1}" }
                matchedChapter = result
                break
            } else {
                logDebug { "  -> No match with Parser #${index + 1}" }
            }
        }
        if (matchedChapter == null) {
            logDebug { "  !!! RESULT: File '${file.name}' did not match any parsers and will be ignored." }
        }
        matchedChapter
    }

    logDebug { "scanForManga: --- Scan Finished. Found ${allChapters.size} valid manga chapters that will be processed. ---" }
    return allChapters.groupBy { it.title }
}
