package com.mangacombiner.util

import com.mangacombiner.service.CachedChapter
import kotlin.math.min

/**
 * A natural sort comparator for strings containing numbers.
 * It parses numbers within the strings and compares them numerically.
 * For example, it correctly sorts "Chapter 2" before "Chapter 10".
 */
private val naturalSortComparator = Comparator<String> { a, b ->
    val parts1 = SlugUtils.parseChapterSlugsForSorting(a)
    val parts2 = SlugUtils.parseChapterSlugsForSorting(b)

    // Fallback to alphabetical sort if numbers aren't found in one of the strings
    if (parts1.isEmpty() || parts2.isEmpty()) return@Comparator a.compareTo(b)

    // Compare each numerical part
    val maxIndex = min(parts1.size, parts2.size)
    for (i in 0 until maxIndex) {
        val compare = parts1[i].compareTo(parts2[i])
        if (compare != 0) return@Comparator compare
    }

    // If all numerical parts are equal, the one with fewer parts comes first
    return@Comparator parts1.size.compareTo(parts2.size)
}

/**
 * A specific comparator for `Pair<String, String>` types, sorting by the second string.
 */
val ChapterPairComparator: Comparator<Pair<String, String>> = compareBy(naturalSortComparator) { it.second }

/**
 * A specific comparator for the `CachedChapter` data class, sorting by its name.
 */
val CachedChapterNameComparator: Comparator<CachedChapter> = compareBy(naturalSortComparator) { it.name }
