package com.mangacombiner.model

/**
 * Represents a single search result from the manga website.
 *
 * @property title The title of the manga series.
 * @property url The URL to the manga's main page.
 * @property thumbnailUrl The URL for the manga's cover image.
 * @property isFetchingDetails True if the app is currently fetching chapter details for this item.
 * @property isExpanded True if the user has expanded this item in the UI to see the chapter list.
 * @property chapterCount The total number of chapters found.
 * @property chapterRange A display string for the chapter range (e.g., "1-100").
 * @property chapters A list of all found chapter titles.
 */
data class SearchResult(
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val isFetchingDetails: Boolean = false,
    val isExpanded: Boolean = false,
    val chapterCount: Int? = null,
    val chapterRange: String? = null,
    val chapters: List<Pair<String, String>> = emptyList()
)
