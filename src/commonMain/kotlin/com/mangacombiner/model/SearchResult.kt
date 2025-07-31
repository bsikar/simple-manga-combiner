package com.mangacombiner.model

/**
 * Represents a single search result from the manga website.
 */
data class SearchResult(
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val isFetchingDetails: Boolean = Defaults.IS_FETCHING_DETAILS,
    val isExpanded: Boolean = Defaults.IS_EXPANDED,
    val chapterCount: Int? = Defaults.CHAPTER_COUNT,
    val chapterRange: String? = Defaults.CHAPTER_RANGE,
    val chapters: List<Pair<String, String>> = Defaults.CHAPTERS,
    val genres: List<String>? = Defaults.GENRES
) {
    companion object Defaults {
        const val IS_FETCHING_DETAILS = false
        const val IS_EXPANDED = false
        val CHAPTER_COUNT: Int? = null
        val CHAPTER_RANGE: String? = null
        val CHAPTERS: List<Pair<String, String>> = emptyList()
        val GENRES: List<String>? = null
    }
}
