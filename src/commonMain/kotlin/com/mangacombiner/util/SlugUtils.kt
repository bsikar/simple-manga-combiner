package com.mangacombiner.util

object SlugUtils {
    private val numberRegex = "\\d+".toRegex()
    private const val CHAPTER_NUMBER_PADDING = 4

    /**
     * Creates a simplified, comparable key from a chapter title or slug.
     * It relies on extracting all numbers from the string.
     * e.g., "Chapter 10.5" and "chapter-10-5" both become "10-5".
     * If no numbers are found, it falls back to a letter-only key.
     */
    fun toComparableKey(text: String): String {
        val numbers = numberRegex.findAll(text).toList()
        if (numbers.isEmpty()) {
            // Fallback for non-numbered chapters like "Omake" or "Extra"
            return text.filter { it.isLetter() }.lowercase()
        }
        return numbers
            .map { it.value.toInt() } // Convert to Int to remove leading zeros (e.g., 01 -> 1)
            .joinToString("-")
    }

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
