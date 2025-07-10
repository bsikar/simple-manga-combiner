package com.mangacombiner.ui.viewmodel.state

import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.model.SearchResult
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadResult
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.OperationState

data class UiState(
    val currentScreen: Screen = Screen.DOWNLOAD,
    val theme: AppTheme = AppTheme.LIGHT,
    val seriesUrl: String = "",
    val customTitle: String = "",
    val outputPath: String = "",
    val defaultOutputLocation: String = "Downloads",
    val customDefaultOutputPath: String = "",
    val workers: Int = 4,
    val batchWorkers: Int = 1,
    val outputFormat: String = "epub",
    val userAgentName: String = "Chrome (Windows)",
    val perWorkerUserAgent: Boolean = false,
    val debugLog: Boolean = false,
    val operationState: OperationState = OperationState.IDLE,
    val progress: Float = 0f,
    val progressStatusText: String = "",
    val completionMessage: String? = null,
    val isFetchingChapters: Boolean = false,
    val isAnalyzingFile: Boolean = false,
    val fetchedChapters: List<Chapter> = emptyList(),
    val localChaptersForSync: Map<String, String> = emptyMap(),
    val failedItemsForSync: Map<String, List<String>> = emptyMap(),
    val sourceFilePath: String? = null,
    val showChapterDialog: Boolean = false,
    val showClearCacheDialog: Boolean = false,
    val showCancelDialog: Boolean = false,
    val deleteCacheOnCancel: Boolean = false,
    val showDeleteCacheConfirmationDialog: Boolean = false,
    val cacheContents: List<CachedSeries> = emptyList(),
    val cacheItemsToDelete: Set<String> = emptySet(),
    val cacheSortState: Map<String, CacheSortState?> = emptyMap(),
    val expandedCacheSeries: Set<String> = emptySet(),
    val logAutoscrollEnabled: Boolean = true,
    val activeDownloadOptions: DownloadOptions? = null,
    val settingsLocationDescription: String = "",
    val isSettingsLocationOpenable: Boolean = false,
    val cachePath: String = "",
    val zoomFactor: Float = 1.0f,
    val fontSizePreset: String = "Medium",
    val showRestoreDefaultsDialog: Boolean = false,
    val outputFileExists: Boolean = false,
    val showOverwriteConfirmationDialog: Boolean = false,
    val showAboutDialog: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val originalSearchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val searchSortOption: SearchSortOption = SearchSortOption.DEFAULT,
    val downloadQueue: List<DownloadJob> = emptyList(),
    val overallQueueProgress: Float = 0f,
    val editingJobId: String? = null,
    val editingJobContext: QueuedOperation? = null,
    val showBrokenDownloadDialog: Boolean = false,
    val showCompletionDialog: Boolean = false,
    val lastDownloadResult: DownloadResult? = null,
    val chaptersToPreselect: Set<String> = emptySet(),
    val isOfflineMode: Boolean = false
)

internal fun UiState.toAppSettings() = AppSettings(
    theme = this.theme,
    defaultOutputLocation = this.defaultOutputLocation,
    customDefaultOutputPath = this.customDefaultOutputPath,
    workers = this.workers,
    batchWorkers = this.batchWorkers,
    outputFormat = this.outputFormat,
    userAgentName = this.userAgentName,
    perWorkerUserAgent = this.perWorkerUserAgent,
    debugLog = this.debugLog,
    logAutoscrollEnabled = this.logAutoscrollEnabled,
    zoomFactor = this.zoomFactor,
    fontSizePreset = this.fontSizePreset
)
