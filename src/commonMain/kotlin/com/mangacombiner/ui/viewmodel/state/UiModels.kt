package com.mangacombiner.ui.viewmodel.state

import kotlinx.serialization.Serializable

enum class Screen {
    DOWNLOAD,
    SEARCH,
    WEB_DAV,
    DOWNLOAD_QUEUE,
    LIBRARY,
    LOGS,
    SETTINGS,
    CACHE_VIEWER
}

enum class ChapterSource {
    LOCAL, CACHE, WEB
}

enum class ProxyStatus {
    UNVERIFIED, VERIFYING, CONNECTED, FAILED
}

@Serializable
data class Chapter(
    val url: String,
    val title: String,
    val availableSources: Set<ChapterSource>,
    var selectedSource: ChapterSource?,
    val localSlug: String?,
    val isRetry: Boolean = false,
    val isBroken: Boolean = false
)

enum class RangeAction {
    SELECT, DESELECT
}

enum class SortDirection {
    ASC, DESC
}

enum class SortCriteria {
    NAME, SIZE
}

enum class SearchSortOption {
    DEFAULT, CHAPTER_COUNT, ALPHABETICAL
}

enum class LibrarySortOption {
    DEFAULT, TITLE_ASC, TITLE_DESC
}

data class CacheSortState(val criteria: SortCriteria, val direction: SortDirection)

enum class ReaderTheme {
    BLACK, WHITE, SEPIA
}
