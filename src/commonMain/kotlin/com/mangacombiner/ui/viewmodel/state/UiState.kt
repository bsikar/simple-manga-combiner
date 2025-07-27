package com.mangacombiner.ui.viewmodel.state

import com.mangacombiner.model.*
import com.mangacombiner.service.*
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.service.ProxyMonitorService
import com.mangacombiner.ui.viewmodel.OperationState

data class UiState(
    val currentScreen: Screen = Screen.SEARCH,
    val showAboutDialog: Boolean = false,
    val showRestoreDefaultsDialog: Boolean = false,

    // Settings
    val theme: AppTheme = AppSettings.Defaults.THEME,
    val defaultOutputLocation: String = AppSettings.Defaults.DEFAULT_OUTPUT_LOCATION,
    val customDefaultOutputPath: String = AppSettings.Defaults.CUSTOM_DEFAULT_OUTPUT_PATH,
    val workers: Int = AppSettings.Defaults.WORKERS,
    val batchWorkers: Int = AppSettings.Defaults.BATCH_WORKERS,
    val outputFormat: String = AppSettings.Defaults.OUTPUT_FORMAT,
    val userAgentName: String = AppSettings.Defaults.USER_AGENT_NAME,
    val perWorkerUserAgent: Boolean = AppSettings.Defaults.PER_WORKER_USER_AGENT,
    val proxyUrl: String = AppSettings.Defaults.PROXY_URL,
    val proxyType: ProxyType = AppSettings.Defaults.PROXY_TYPE,
    val proxyHost: String = AppSettings.Defaults.PROXY_HOST,
    val proxyPort: String = AppSettings.Defaults.PROXY_PORT,
    val proxyUser: String = AppSettings.Defaults.PROXY_USER,
    val proxyPass: String = AppSettings.Defaults.PROXY_PASS,
    val debugLog: Boolean = AppSettings.Defaults.DEBUG_LOG,
    val logAutoscrollEnabled: Boolean = AppSettings.Defaults.LOG_AUTOSCROLL_ENABLED,
    val settingsLocationDescription: String = "",
    val isSettingsLocationOpenable: Boolean = false,
    val isCacheLocationOpenable: Boolean = false,
    val cachePath: String = "",
    val zoomFactor: Float = AppSettings.Defaults.ZOOM_FACTOR,
    val fontSizePreset: String = AppSettings.Defaults.FONT_SIZE_PRESET,
    val offlineMode: Boolean = AppSettings.Defaults.OFFLINE_MODE,
    val proxyEnabledOnStartup: Boolean = AppSettings.Defaults.PROXY_ENABLED_ON_STARTUP,
    val ipLookupUrl: String = AppSettings.Defaults.IP_LOOKUP_URL,
    val customIpLookupUrl: String = AppSettings.Defaults.CUSTOM_IP_LOOKUP_URL,

    // Proxy status
    val proxyStatus: ProxyStatus = ProxyStatus.UNVERIFIED,
    val proxyVerificationMessage: String? = null,
    val ipInfoResult: IpInfo? = null,
    val ipCheckError: String? = null,
    val isCheckingIp: Boolean = false,
    val proxyConnectionState: ProxyMonitorService.ProxyConnectionState = ProxyMonitorService.ProxyConnectionState.UNKNOWN,
    val killSwitchActive: Boolean = false,

    // Search state
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val originalSearchResults: List<SearchResult> = emptyList(),
    val searchSortOption: SearchSortOption = SearchSortOption.DEFAULT,
    val isSearching: Boolean = false,
    val searchSource: String = "mangaread.org",
    val searchSources: List<String> = listOf("mangaread.org", "manhwaus.net"),

    // Download state
    val seriesUrl: String = "",
    val customTitle: String = "",
    val outputPath: String = "",
    val outputFileExists: Boolean = false,
    val sourceFilePath: String? = null,
    val localChaptersForSync: Map<String, String> = emptyMap(),
    val failedItemsForSync: Map<String, List<String>> = emptyMap(),
    val fetchedChapters: List<Chapter> = emptyList(),
    val seriesMetadata: SeriesMetadata? = null,
    val isFetchingChapters: Boolean = false,
    val isAnalyzingFile: Boolean = false,
    val showChapterDialog: Boolean = false,
    val chaptersToPreselect: Set<String> = emptySet(),

    // Operation state
    val operationState: OperationState = OperationState.IDLE,
    val progress: Float = 0f,
    val progressStatusText: String = "",
    val activeDownloadOptions: DownloadOptions? = null,
    val showCancelDialog: Boolean = false,
    val deleteCacheOnCancel: Boolean = false,
    val showOverwriteConfirmationDialog: Boolean = false,
    val showBrokenDownloadDialog: Boolean = false,
    val lastDownloadResult: DownloadResult? = null,
    val showCompletionDialog: Boolean = false,
    val completionMessage: String? = null,
    val showNetworkErrorDialog: Boolean = false,
    val networkErrorMessage: String? = null,

    // Queue state
    val downloadQueue: List<DownloadJob> = emptyList(),
    val overallQueueProgress: Float = 0f,
    val editingJobId: String? = null,
    val editingJobContext: QueuedOperation? = null,
    val editingJobIdForChapters: String? = null,
    val showAddDuplicateDialog: Boolean = false,
    val jobContextToAdd: QueuedOperation? = null,
    val isQueueGloballyPaused: Boolean = false,
    val isNetworkBlocked: Boolean = false,
    val isInitialProxyCheckRunning: Boolean = false,

    // Cache state
    val cacheContents: List<CachedSeries> = emptyList(),
    val expandedCacheSeries: Set<String> = emptySet(),
    val cacheItemsToDelete: Set<String> = emptySet(),
    val cacheSortState: Map<String, CacheSortState> = emptyMap(),
    val showClearCacheDialog: Boolean = false,
    val showDeleteCacheConfirmationDialog: Boolean = false,

    // WebDAV state
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPass: String = "",
    val webDavIncludeHidden: Boolean = false,
    val webDavFiles: List<WebDavFile> = emptyList(),
    val webDavFileCache: Map<String, WebDavFile> = emptyMap(),
    val webDavSelectedFiles: Set<String> = emptySet(),
    val webDavFolderSizes: Map<String, Long?> = emptyMap(),
    val webDavFilterQuery: String = "",
    val webDavSortState: CacheSortState = CacheSortState(SortCriteria.NAME, SortDirection.ASC),
    val isConnectingToWebDav: Boolean = false,
    val webDavError: String? = null,
    val isDownloadingFromWebDav: Boolean = false,
    val webDavDownloadProgress: Float = 0f,
    val webDavStatus: String = ""
)

// Extension function to convert UiState to AppSettings
fun UiState.toAppSettings() = AppSettings(
    theme = theme,
    defaultOutputLocation = defaultOutputLocation,
    customDefaultOutputPath = customDefaultOutputPath,
    workers = workers,
    batchWorkers = batchWorkers,
    outputFormat = outputFormat,
    userAgentName = userAgentName,
    perWorkerUserAgent = perWorkerUserAgent,
    proxyUrl = proxyUrl,
    proxyType = proxyType,
    proxyHost = proxyHost,
    proxyPort = proxyPort,
    proxyUser = proxyUser,
    proxyPass = proxyPass,
    debugLog = debugLog,
    logAutoscrollEnabled = logAutoscrollEnabled,
    zoomFactor = zoomFactor,
    fontSizePreset = fontSizePreset,
    offlineMode = offlineMode,
    proxyEnabledOnStartup = proxyEnabledOnStartup,
    ipLookupUrl = ipLookupUrl,
    customIpLookupUrl = customIpLookupUrl
)
