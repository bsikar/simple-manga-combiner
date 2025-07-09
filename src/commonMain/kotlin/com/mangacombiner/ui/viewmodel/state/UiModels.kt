package com.mangacombiner.ui.viewmodel.state

enum class Screen {
    DOWNLOAD,
    DOWNLOAD_QUEUE,
    LOGS,
    ADVANCED_SETTINGS,
    SETTINGS,
    CACHE_VIEWER
}

enum class ChapterSource {
    LOCAL, CACHE, WEB
}

data class Chapter(
    val url: String,
    val title: String,
    val availableSources: Set<ChapterSource>,
    var selectedSource: ChapterSource?,
    val localSlug: String?
)

enum class RangeAction {
    SELECT, DESELECT, TOGGLE
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

data class CacheSortState(val criteria: SortCriteria, val direction: SortDirection)
