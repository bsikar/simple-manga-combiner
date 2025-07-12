package com.mangacombiner.ui.viewmodel

import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.state.CacheSortState
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.ui.viewmodel.state.RangeAction
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.viewmodel.state.SearchSortOption

sealed interface Event {
    data class Navigate(val screen: Screen) : Event
    data class ToggleAboutDialog(val show: Boolean) : Event

    sealed interface Settings : Event {
        data class UpdateTheme(val theme: AppTheme) : Settings
        data class UpdateFontSizePreset(val preset: String) : Settings
        data class UpdateDefaultOutputLocation(val location: String) : Settings
        data class ToggleDebugLog(val isEnabled: Boolean) : Settings
        data class UpdateWorkers(val count: Int) : Settings
        data class UpdateBatchWorkers(val count: Int) : Settings
        data class UpdateUserAgent(val name: String) : Settings
        data class UpdateProxyUrl(val url: String) : Settings
        data class TogglePerWorkerUserAgent(val isEnabled: Boolean) : Settings
        data class ToggleOfflineMode(val isEnabled: Boolean) : Settings
        object PickCustomDefaultPath : Settings
        object OpenSettingsLocation : Settings
        object OpenCacheLocation : Settings
        object ZoomIn : Settings
        object ZoomOut : Settings
        object ZoomReset : Settings
        object RequestRestoreDefaults : Settings
        object ConfirmRestoreDefaults : Settings
        object CancelRestoreDefaults : Settings
    }

    sealed interface Search : Event {
        data class UpdateQuery(val query: String) : Search
        data class SelectResult(val url: String) : Search
        data class SortResults(val sortOption: SearchSortOption) : Search
        data class ToggleResultExpansion(val url: String) : Search
        object Perform : Search
    }

    sealed interface Download : Event {
        data class UpdateUrl(val url: String) : Download
        data class UpdateCustomTitle(val title: String) : Download
        data class UpdateOutputPath(val path: String) : Download
        data class UpdateFormat(val format: String) : Download
        data class UpdateChapterSource(val chapterUrl: String, val source: ChapterSource?) : Download
        data class ToggleChapterSelection(val chapterUrl: String, val select: Boolean) : Download
        data class ToggleChapterRedownload(val chapterUrl: String) : Download
        data class UpdateChapterRange(val start: Int, val end: Int, val action: RangeAction) : Download
        object PickOutputPath : Download
        object PickLocalFile : Download
        object ClearInputs : Download
        object FetchChapters : Download
        object ConfirmChapterSelection : Download
        object CancelChapterSelection : Download
        object SelectAllChapters : Download
        object DeselectAllChapters : Download
        object UseAllLocal : Download
        object IgnoreAllLocal : Download
        object RedownloadAllLocal : Download
        object UseAllCached : Download
        object IgnoreAllCached : Download
        object RedownloadAllCached : Download
        object UseAllBroken : Download
        object IgnoreAllBroken : Download
        object RedownloadAllBroken : Download
    }

    sealed interface Operation : Event {
        object RequestStart : Operation
        object ConfirmOverwrite : Operation
        object CancelOverwrite : Operation
        object Pause : Operation
        object Resume : Operation
        object RequestCancel : Operation
        object ConfirmCancel : Operation
        object AbortCancel : Operation
        data class ToggleDeleteCacheOnCancel(val delete: Boolean) : Operation
        object ConfirmBrokenDownload : Operation
        object DiscardFailed : Operation
        object RetryFailed : Operation
    }

    sealed interface Cache : Event {
        data class LoadCachedSeries(val seriesPath: String) : Cache
        data class SetItemForDeletion(val path: String, val select: Boolean) : Cache
        data class UpdateChapterRange(val seriesPath: String, val start: Int, val end: Int, val action: RangeAction) : Cache
        data class SelectAllChapters(val seriesPath: String, val select: Boolean) : Cache
        data class SetSort(val seriesPath: String, val sortState: CacheSortState?) : Cache
        data class ToggleSeries(val seriesPath: String) : Cache
        object RequestClearAll : Cache
        object ConfirmClearAll : Cache
        object CancelClearAll : Cache
        object RefreshView : Cache
        object RequestDeleteSelected : Cache
        object ConfirmDeleteSelected : Cache
        object CancelDeleteSelected : Cache
        object SelectAll : Cache
        object DeselectAll : Cache
    }

    sealed interface Queue : Event {
        enum class MoveDirection { UP, DOWN }
        object Add : Queue
        object ClearCompleted : Queue
        object PauseAll : Queue
        object ResumeAll : Queue
        data class RequestEditJob(val jobId: String) : Queue
        data class UpdateJob(
            val jobId: String,
            val title: String,
            val outputPath: String,
            val format: String,
            val workers: Int
        ) : Queue
        object CancelEditJob : Queue
        object PickJobOutputPath : Queue
        data class CancelJob(val jobId: String) : Queue
        data class TogglePauseJob(val jobId: String, val force: Boolean? = null) : Queue
        data class MoveJob(val jobId: String, val direction: MoveDirection) : Queue
    }

    sealed interface Log : Event {
        object ToggleAutoscroll : Log
        object CopyToClipboard : Log
        object Clear : Log
    }
}
