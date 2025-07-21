package com.mangacombiner.ui.viewmodel.state

import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.model.SearchResult
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadResult
import com.mangacombiner.service.WebDavFile
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.OperationState

data class UiState(
    // Transient UI State (Defaults live here)
    val currentScreen: Screen = Screen.SEARCH,
    val seriesUrl: String = "",
    val customTitle: String = "",
    val outputPath: String = "",
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
    val isCacheLocationOpenable: Boolean = false,
    val cacheContents: List<CachedSeries> = emptyList(),
    val cacheItemsToDelete: Set<String> = emptySet(),
    val cacheSortState: Map<String, CacheSortState?> = emptyMap(),
    val expandedCacheSeries: Set<String> = emptySet(),
    val activeDownloadOptions: DownloadOptions? = null,
    val settingsLocationDescription: String = "",
    val isSettingsLocationOpenable: Boolean = false,
    val cachePath: String = "",
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
    val editingJobIdForChapters: String? = null,
    val editingJobContext: QueuedOperation? = null,
    val showBrokenDownloadDialog: Boolean = false,
    val showCompletionDialog: Boolean = false,
    val lastDownloadResult: DownloadResult? = null,
    val chaptersToPreselect: Set<String> = emptySet(),
    val isQueueGloballyPaused: Boolean = false,
    val showAddDuplicateDialog: Boolean = false,
    val jobContextToAdd: QueuedOperation? = null,
    val showNetworkErrorDialog: Boolean = false,
    val networkErrorMessage: String? = null,

    // WebDAV State
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPass: String = "",
    val isConnectingToWebDav: Boolean = false,
    val isDownloadingFromWebDav: Boolean = false,
    val webDavDownloadProgress: Float = 0f,
    val webDavStatus: String = "",
    val webDavFileCache: Map<String, WebDavFile> = emptyMap(),
    val webDavFiles: List<WebDavFile> = emptyList(),
    val webDavSelectedFiles: Set<String> = emptySet(),
    val webDavError: String? = null,
    val webDavIncludeHidden: Boolean = false,
    val webDavSortState: CacheSortState = CacheSortState(SortCriteria.NAME, SortDirection.ASC),
    val webDavFilterQuery: String = "",
    val webDavFolderSizes: Map<String, Long?> = emptyMap(),

    // Persisted Settings (Defaults point to AppSettings.Defaults)
    val theme: AppTheme = AppSettings.Defaults.THEME,
    val defaultOutputLocation: String = AppSettings.Defaults.DEFAULT_OUTPUT_LOCATION,
    val customDefaultOutputPath: String = AppSettings.Defaults.CUSTOM_DEFAULT_OUTPUT_PATH,
    val workers: Int = AppSettings.Defaults.WORKERS,
    val batchWorkers: Int = AppSettings.Defaults.BATCH_WORKERS,
    val outputFormat: String = AppSettings.Defaults.OUTPUT_FORMAT,
    val userAgentName: String = AppSettings.Defaults.USER_AGENT_NAME,
    val perWorkerUserAgent: Boolean = AppSettings.Defaults.PER_WORKER_USER_AGENT,
    val proxyUrl: String = AppSettings.Defaults.PROXY_URL,
    val debugLog: Boolean = AppSettings.Defaults.DEBUG_LOG,
    val logAutoscrollEnabled: Boolean = AppSettings.Defaults.LOG_AUTOSCROLL_ENABLED,
    val zoomFactor: Float = AppSettings.Defaults.ZOOM_FACTOR,
    val fontSizePreset: String = AppSettings.Defaults.FONT_SIZE_PRESET,
    val offlineMode: Boolean = AppSettings.Defaults.OFFLINE_MODE
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
    proxyUrl = this.proxyUrl,
    debugLog = this.debugLog,
    logAutoscrollEnabled = this.logAutoscrollEnabled,
    zoomFactor = this.zoomFactor,
    fontSizePreset = this.fontSizePreset,
    offlineMode = this.offlineMode
)
