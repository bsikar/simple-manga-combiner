package com.mangacombiner.util

object SlugUtils {
    private val numberRegex = "\\d+".toRegex()
    private const val CHAPTER_NUMBER_PADDING = 4

    fun parseChapterSlugsForSorting(slug: String): List<Int> {
        return numberRegex.findAll(slug).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    fun normalizeChapterSlug(slug: String): String {
        val matches = numberRegex.findAll(slug).toList()

        if (matches.isEmpty()) {
            return slug.replace(Regex("[_\\-]"), ".")
        }

        val sb = StringBuilder()
        var lastEnd = 0

        matches.forEach { match ->
            sb.append(slug.substring(lastEnd, match.range.first))
            sb.append(match.value.padStart(CHAPTER_NUMBER_PADDING, '0'))
            lastEnd = match.range.last + 1
        }

        if (lastEnd < slug.length) {
            sb.append(slug.substring(lastEnd))
        }

        return sb.toString().replace(Regex("[_\\-]"), ".")
    }
}
