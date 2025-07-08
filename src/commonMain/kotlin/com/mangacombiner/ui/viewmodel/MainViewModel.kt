package com.mangacombiner.ui.viewmodel

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.model.AppSettings
import com.mangacombiner.service.CacheService
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.util.ChapterPairComparator
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.logOperationSettings
import com.mangacombiner.util.showFilePicker
import com.mangacombiner.util.showFolderPicker
import com.mangacombiner.util.titlecase
import com.mangacombiner.util.toSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

enum class Screen {
    DOWNLOAD,
    FILE_UPDATER,
    SETTINGS,
    CACHE_VIEWER
}

data class Chapter(
    val url: String,
    val title: String,
    var isSelected: Boolean = true,
    val isCached: Boolean = false
)
data class ChapterInFile(
    val name: String,
    var isSelected: Boolean = true
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

data class CacheSortState(val criteria: SortCriteria, val direction: SortDirection)

data class UiState(
    val currentScreen: Screen = Screen.DOWNLOAD,
    val theme: AppTheme = AppTheme.DARK,
    val seriesUrl: String = "",
    val customTitle: String = "",
    val outputPath: String = "",
    val defaultOutputLocation: String = "Downloads",
    val customDefaultOutputPath: String = "",
    val workers: Int = 4,
    val outputFormat: String = "epub",
    val userAgentName: String = "Chrome (Windows)",
    val perWorkerUserAgent: Boolean = false,
    val debugLog: Boolean = false,
    val dryRun: Boolean = false,
    val operationState: OperationState = OperationState.IDLE,
    val progress: Float = 0f,
    val progressStatusText: String = "",
    val isFetchingChapters: Boolean = false,
    val fetchedChapters: List<Chapter> = emptyList(),
    val chapterCacheOverrides: Set<String> = emptySet(),
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
    val zoomFactor: Float = 1.0f,
    val fontSizePreset: String = "Medium",
    val systemLightTheme: AppTheme = AppTheme.LIGHT,
    val systemDarkTheme: AppTheme = AppTheme.DARK,
    val showRestoreDefaultsDialog: Boolean = false,
    // Local File Updater State
    val localFilePath: String = "",
    val localFileChapters: List<ChapterInFile> = emptyList(),
    val isAnalyzingFile: Boolean = false
)

/**
 * Helper function to map the full UI state to just the part we want to save.
 */
private fun UiState.toAppSettings() = AppSettings(
    theme = this.theme,
    defaultOutputLocation = this.defaultOutputLocation,
    customDefaultOutputPath = this.customDefaultOutputPath,
    workers = this.workers,
    outputFormat = this.outputFormat,
    userAgentName = this.userAgentName,
    perWorkerUserAgent = this.perWorkerUserAgent,
    debugLog = this.debugLog,
    logAutoscrollEnabled = this.logAutoscrollEnabled,
    zoomFactor = this.zoomFactor,
    fontSizePreset = this.fontSizePreset,
    systemLightTheme = this.systemLightTheme,
    systemDarkTheme = this.systemDarkTheme
)

@OptIn(FlowPreview::class)
class MainViewModel(
    private val downloadService: DownloadService,
    private val scraperService: ScraperService,
    private val clipboardManager: ClipboardManager,
    private val platformProvider: PlatformProvider,
    private val cacheService: CacheService,
    private val settingsRepository: SettingsRepository
) : PlatformViewModel() {

    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState>

    private val _operationState = MutableStateFlow(OperationState.IDLE)

    private val _logs = MutableStateFlow(listOf("Welcome to Manga Combiner!"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        val savedSettings = settingsRepository.loadSettings()
        Logger.isDebugEnabled = savedSettings.debugLog
        _state = MutableStateFlow(
            UiState(
                theme = savedSettings.theme,
                defaultOutputLocation = savedSettings.defaultOutputLocation,
                customDefaultOutputPath = savedSettings.customDefaultOutputPath,
                workers = savedSettings.workers,
                outputFormat = savedSettings.outputFormat,
                userAgentName = savedSettings.userAgentName,
                perWorkerUserAgent = savedSettings.perWorkerUserAgent,
                debugLog = savedSettings.debugLog,
                logAutoscrollEnabled = savedSettings.logAutoscrollEnabled,
                settingsLocationDescription = platformProvider.getSettingsLocationDescription(),
                isSettingsLocationOpenable = platformProvider.isSettingsLocationOpenable(),
                zoomFactor = savedSettings.zoomFactor,
                fontSizePreset = savedSettings.fontSizePreset,
                systemLightTheme = savedSettings.systemLightTheme,
                systemDarkTheme = savedSettings.systemDarkTheme
            )
        )
        state = _state.asStateFlow()

        Logger.addListener { logMessage ->
            _logs.update { it + logMessage }
        }
        viewModelScope.launch {
            _operationState.collect {
                _state.update { uiState -> uiState.copy(operationState = it) }
            }
        }

        // Set initial output path based on saved settings
        val defaultDownloadsPath = platformProvider.getUserDownloadsDir() ?: ""
        val initialOutputPath = when (savedSettings.defaultOutputLocation) {
            "Downloads" -> defaultDownloadsPath
            "Documents" -> platformProvider.getUserDocumentsDir() ?: ""
            "Desktop" -> platformProvider.getUserDesktopDir() ?: ""
            "Custom" -> savedSettings.customDefaultOutputPath
            else -> ""
        }
        _state.update {
            it.copy(
                outputPath = initialOutputPath,
                customDefaultOutputPath = if (savedSettings.defaultOutputLocation == "Custom") savedSettings.customDefaultOutputPath else defaultDownloadsPath
            )
        }


        // Automatically save settings whenever they change
        viewModelScope.launch {
            state
                .debounce(500L)
                .map { it.toAppSettings() }
                .distinctUntilChanged()
                .collect { settingsToSave ->
                    settingsRepository.saveSettings(settingsToSave)
                    Logger.logDebug { "Settings saved automatically." }
                }
        }
    }

    sealed class Event {
        data class Navigate(val screen: Screen) : Event()
        data class UpdateUrl(val url: String) : Event()
        data class UpdateCustomTitle(val title: String) : Event()
        data class UpdateOutputPath(val path: String) : Event()
        data class UpdateWorkers(val count: Int) : Event()
        data class UpdateFormat(val format: String) : Event()
        data class ToggleDebugLog(val isEnabled: Boolean) : Event()
        data class ToggleDryRun(val isEnabled: Boolean) : Event()
        data class UpdateUserAgent(val name: String) : Event()
        data class TogglePerWorkerUserAgent(val isEnabled: Boolean) : Event()
        data class UpdateTheme(val theme: AppTheme) : Event()
        data class ToggleChapterSelection(val chapterUrl: String, val isSelected: Boolean) : Event()
        data class UpdateChapterRange(val start: Int, val end: Int, val action: RangeAction) : Event()
        data class ToggleCacheItemForDeletion(val path: String) : Event()
        data class UpdateCachedChapterRange(val seriesPath: String, val start: Int, val end: Int, val action: RangeAction) : Event()
        data class SelectAllCachedChapters(val seriesPath: String, val select: Boolean) : Event()
        data class ToggleDeleteCacheOnCancel(val delete: Boolean) : Event()
        data class UpdateDefaultOutputLocation(val location: String) : Event()
        data class SetCacheSort(val seriesPath: String, val sortState: CacheSortState?) : Event()
        data class ToggleCacheSeries(val seriesPath: String) : Event()
        data class ToggleChapterCacheOverride(val chapterUrl: String) : Event()
        data class UpdateFontSizePreset(val preset: String) : Event()
        data class UpdateSystemLightTheme(val theme: AppTheme) : Event()
        data class UpdateSystemDarkTheme(val theme: AppTheme) : Event()
        object PickCustomDefaultPath : Event()
        object PickOutputPath : Event()
        object ToggleLogAutoscroll : Event()
        object CopyLogsToClipboard : Event()
        object ClearLogs : Event()
        object FetchChapters : Event()
        object ConfirmChapterSelection : Event()
        object CancelChapterSelection : Event()
        object SelectAllChapters : Event()
        object DeselectAllChapters : Event()
        object StartOperation : Event()
        object PauseOperation : Event()
        object ResumeOperation : Event()
        object RequestCancelOperation : Event()
        object ConfirmCancelOperation : Event()
        object AbortCancelOperation : Event()
        object RequestClearAllCache : Event()
        object ConfirmClearAllCache : Event()
        object CancelClearAllCache : Event()
        object RefreshCacheView : Event()
        object RequestDeleteSelectedCacheItems : Event()
        object ConfirmDeleteSelectedCacheItems : Event()
        object CancelDeleteSelectedCacheItems : Event()
        object SelectAllCache : Event()
        object DeselectAllCache : Event()
        object SelectAllCacheOverrides : Event()
        object DeselectAllCacheOverrides : Event()
        object ToggleAllCacheOverrides : Event()
        object OpenSettingsLocation : Event()
        object ZoomIn : Event()
        object ZoomOut : Event()
        object ZoomReset : Event()
        object RequestRestoreDefaults : Event()
        object ConfirmRestoreDefaults : Event()
        object CancelRestoreDefaults : Event()

        // Local File Updater Events
        object PickLocalFile : Event()
        data class LoadLocalFile(val path: String) : Event()
        data class ToggleLocalFileChapterSelection(val chapterName: String, val isSelected: Boolean) : Event()
        object StartLocalFileUpdate : Event()
    }

    fun onEvent(event: Event) {
        when (event) {
            is Event.UpdateDefaultOutputLocation -> {
                val newPath = when (event.location) {
                    "Downloads" -> platformProvider.getUserDownloadsDir()
                    "Documents" -> platformProvider.getUserDocumentsDir()
                    "Desktop" -> platformProvider.getUserDesktopDir()
                    "Custom" -> _state.value.customDefaultOutputPath
                    else -> ""
                } ?: ""
                _state.update { it.copy(defaultOutputLocation = event.location, outputPath = newPath) }
            }
            Event.PickCustomDefaultPath -> {
                showFolderPicker { path ->
                    if (path != null) {
                        _state.update { it.copy(customDefaultOutputPath = path, outputPath = path) }
                    }
                }
            }
            Event.PickOutputPath -> {
                showFolderPicker { path ->
                    if (path != null) {
                        _state.update { it.copy(outputPath = path) }
                    }
                }
            }
            Event.OpenSettingsLocation -> {
                viewModelScope.launch(Dispatchers.IO) {
                    platformProvider.openSettingsLocation()
                }
            }
            Event.ZoomIn -> {
                _state.update { it.copy(zoomFactor = (it.zoomFactor + 0.1f).coerceIn(0.5f, 2.0f)) }
            }
            Event.ZoomOut -> {
                _state.update { it.copy(zoomFactor = (it.zoomFactor - 0.1f).coerceIn(0.5f, 2.0f)) }
            }
            Event.ZoomReset -> {
                _state.update { it.copy(zoomFactor = 1.0f) }
            }
            is Event.UpdateFontSizePreset -> _state.update { it.copy(fontSizePreset = event.preset) }
            is Event.UpdateSystemLightTheme -> {
                if (event.theme != AppTheme.SYSTEM) {
                    _state.update { it.copy(systemLightTheme = event.theme) }
                }
            }
            is Event.UpdateSystemDarkTheme -> {
                if (event.theme != AppTheme.SYSTEM) {
                    _state.update { it.copy(systemDarkTheme = event.theme) }
                }
            }
            Event.RequestRestoreDefaults -> _state.update { it.copy(showRestoreDefaultsDialog = true) }
            Event.CancelRestoreDefaults -> _state.update { it.copy(showRestoreDefaultsDialog = false) }
            Event.ConfirmRestoreDefaults -> {
                val defaultSettings = AppSettings()
                _state.update {
                    it.copy(
                        showRestoreDefaultsDialog = false,
                        theme = defaultSettings.theme,
                        defaultOutputLocation = defaultSettings.defaultOutputLocation,
                        customDefaultOutputPath = defaultSettings.customDefaultOutputPath,
                        workers = defaultSettings.workers,
                        outputFormat = defaultSettings.outputFormat,
                        userAgentName = defaultSettings.userAgentName,
                        perWorkerUserAgent = defaultSettings.perWorkerUserAgent,
                        debugLog = defaultSettings.debugLog,
                        logAutoscrollEnabled = defaultSettings.logAutoscrollEnabled,
                        zoomFactor = defaultSettings.zoomFactor,
                        fontSizePreset = defaultSettings.fontSizePreset,
                        systemLightTheme = defaultSettings.systemLightTheme,
                        systemDarkTheme = defaultSettings.systemDarkTheme
                    )
                }
                Logger.logInfo("All settings restored to default values.")
            }
            is Event.UpdateOutputPath -> _state.update { it.copy(outputPath = event.path) }
            is Event.Navigate -> {
                _state.update { it.copy(currentScreen = event.screen) }
                if (event.screen == Screen.CACHE_VIEWER) {
                    onEvent(Event.RefreshCacheView)
                }
            }
            is Event.UpdateUrl -> _state.update { it.copy(seriesUrl = event.url) }
            is Event.UpdateCustomTitle -> _state.update { it.copy(customTitle = event.title) }
            is Event.UpdateWorkers -> _state.update { it.copy(workers = event.count.coerceIn(1, 16)) }
            is Event.UpdateFormat -> _state.update { it.copy(outputFormat = event.format) }
            is Event.ToggleDebugLog -> {
                Logger.isDebugEnabled = event.isEnabled
                _state.update { it.copy(debugLog = event.isEnabled) }
            }
            is Event.ToggleDryRun -> _state.update { it.copy(dryRun = event.isEnabled) }
            is Event.UpdateUserAgent -> _state.update { it.copy(userAgentName = event.name) }
            is Event.TogglePerWorkerUserAgent -> _state.update { it.copy(perWorkerUserAgent = event.isEnabled) }
            is Event.UpdateTheme -> _state.update { it.copy(theme = event.theme) }
            is Event.ToggleDeleteCacheOnCancel -> _state.update { it.copy(deleteCacheOnCancel = event.delete) }
            Event.ToggleLogAutoscroll -> _state.update { it.copy(logAutoscrollEnabled = !it.logAutoscrollEnabled) }
            is Event.ToggleChapterSelection -> {
                _state.update {
                    val updatedChapters = it.fetchedChapters.map { chapter ->
                        if (chapter.url == event.chapterUrl) chapter.copy(isSelected = event.isSelected) else chapter
                    }
                    it.copy(fetchedChapters = updatedChapters)
                }
            }
            is Event.UpdateChapterRange -> updateChapterRange(event.start, event.end, event.action)
            Event.CopyLogsToClipboard -> clipboardManager.copyToClipboard(_logs.value.joinToString("\n"))
            Event.ClearLogs -> _logs.value = listOf("Logs cleared.")
            Event.FetchChapters -> fetchChapters()
            Event.ConfirmChapterSelection -> {
                _state.update { it.copy(showChapterDialog = false) }
            }
            Event.CancelChapterSelection -> {
                _state.update { it.copy(showChapterDialog = false, fetchedChapters = emptyList(), chapterCacheOverrides = emptySet()) }
            }
            Event.SelectAllChapters -> setAllChaptersSelected(true)
            Event.DeselectAllChapters -> setAllChaptersSelected(false)
            Event.StartOperation -> startDownloadOperation()
            Event.PauseOperation -> _operationState.value = OperationState.PAUSED
            Event.ResumeOperation -> {
                logOperationSettings(
                    _state.value.activeDownloadOptions!!,
                    _state.value.activeDownloadOptions!!.chaptersToDownload.size,
                    _state.value.userAgentName,
                    _state.value.perWorkerUserAgent,
                    isResuming = true
                )
                _operationState.value = OperationState.RUNNING
            }
            Event.RequestCancelOperation -> _state.update { it.copy(showCancelDialog = true) }
            Event.AbortCancelOperation -> _state.update { it.copy(showCancelDialog = false) }
            Event.ConfirmCancelOperation -> {
                _state.update { it.copy(showCancelDialog = false) }
                _operationState.value = OperationState.CANCELLING
            }
            Event.RequestClearAllCache -> _state.update { it.copy(showClearCacheDialog = true) }
            Event.CancelClearAllCache -> _state.update { it.copy(showClearCacheDialog = false) }
            Event.ConfirmClearAllCache -> {
                _state.update { it.copy(showClearCacheDialog = false) }
                viewModelScope.launch(Dispatchers.IO) {
                    cacheService.clearAllAppCache()
                    onEvent(Event.RefreshCacheView)
                }
            }
            Event.RefreshCacheView -> {
                viewModelScope.launch(Dispatchers.IO) {
                    _state.update { it.copy(cacheContents = cacheService.getCacheContents()) }
                }
            }
            is Event.ToggleCacheItemForDeletion -> {
                _state.update { uiState ->
                    val newSet = uiState.cacheItemsToDelete.toMutableSet()
                    if (newSet.contains(event.path)) newSet.remove(event.path) else newSet.add(event.path)
                    uiState.copy(cacheItemsToDelete = newSet)
                }
            }
            Event.RequestDeleteSelectedCacheItems -> _state.update { it.copy(showDeleteCacheConfirmationDialog = true) }
            Event.CancelDeleteSelectedCacheItems -> _state.update { it.copy(showDeleteCacheConfirmationDialog = false) }
            Event.ConfirmDeleteSelectedCacheItems -> {
                viewModelScope.launch(Dispatchers.IO) {
                    cacheService.deleteCacheItems(_state.value.cacheItemsToDelete.toList())
                    _state.update { it.copy(cacheItemsToDelete = emptySet(), showDeleteCacheConfirmationDialog = false) }
                    onEvent(Event.RefreshCacheView)
                }
            }
            is Event.SelectAllCachedChapters -> {
                _state.update { uiState ->
                    val series = uiState.cacheContents.find { it.path == event.seriesPath } ?: return@update uiState
                    val chapterPaths = series.chapters.map { it.path }.toSet()
                    val currentSelection = uiState.cacheItemsToDelete.toMutableSet()
                    if (event.select) {
                        currentSelection.addAll(chapterPaths)
                    } else {
                        currentSelection.removeAll(chapterPaths)
                    }
                    uiState.copy(cacheItemsToDelete = currentSelection)
                }
            }
            Event.SelectAllCache -> {
                _state.update { uiState ->
                    val allChapterPaths = uiState.cacheContents.flatMap { series ->
                        series.chapters.map { chapter -> chapter.path }
                    }
                    uiState.copy(cacheItemsToDelete = allChapterPaths.toSet())
                }
            }
            Event.DeselectAllCache -> {
                _state.update { it.copy(cacheItemsToDelete = emptySet()) }
            }
            is Event.UpdateCachedChapterRange -> {
                _state.update { uiState ->
                    val series = uiState.cacheContents.find { it.path == event.seriesPath } ?: return@update uiState
                    val chaptersToUpdate = series.chapters.slice((event.start - 1) until event.end).map { it.path }
                    val currentSelection = uiState.cacheItemsToDelete.toMutableSet()
                    when (event.action) {
                        RangeAction.SELECT -> currentSelection.addAll(chaptersToUpdate)
                        RangeAction.DESELECT -> currentSelection.removeAll(chaptersToUpdate.toSet())
                        RangeAction.TOGGLE -> chaptersToUpdate.forEach { path ->
                            if (currentSelection.contains(path)) currentSelection.remove(path) else currentSelection.add(path)
                        }
                    }
                    uiState.copy(cacheItemsToDelete = currentSelection)
                }
            }
            is Event.SetCacheSort -> {
                _state.update { uiState ->
                    val newSortMap = uiState.cacheSortState.toMutableMap()
                    if (event.sortState == null) {
                        newSortMap.remove(event.seriesPath)
                    } else {
                        newSortMap[event.seriesPath] = event.sortState
                    }
                    uiState.copy(cacheSortState = newSortMap)
                }
            }
            is Event.ToggleCacheSeries -> {
                _state.update { uiState ->
                    val newSet = uiState.expandedCacheSeries.toMutableSet()
                    if (newSet.contains(event.seriesPath)) {
                        newSet.remove(event.seriesPath)
                    } else {
                        newSet.add(event.seriesPath)
                    }
                    uiState.copy(expandedCacheSeries = newSet)
                }
            }
            is Event.ToggleChapterCacheOverride -> {
                _state.update { uiState ->
                    val newSet = uiState.chapterCacheOverrides.toMutableSet()
                    if (newSet.contains(event.chapterUrl)) {
                        newSet.remove(event.chapterUrl)
                    } else {
                        newSet.add(event.chapterUrl)
                    }
                    uiState.copy(chapterCacheOverrides = newSet)
                }
            }
            Event.SelectAllCacheOverrides -> {
                _state.update { uiState ->
                    val allCachedUrls = uiState.fetchedChapters.filter { it.isCached }.map { it.url }.toSet()
                    uiState.copy(chapterCacheOverrides = allCachedUrls)
                }
            }
            Event.DeselectAllCacheOverrides -> {
                _state.update { it.copy(chapterCacheOverrides = emptySet()) }
            }
            Event.ToggleAllCacheOverrides -> {
                _state.update { uiState ->
                    val allCachedUrls = uiState.fetchedChapters.filter { it.isCached }.map { it.url }.toSet()
                    val currentOverrides = uiState.chapterCacheOverrides
                    val newOverrides = allCachedUrls.filter { it !in currentOverrides }.toSet()
                    uiState.copy(chapterCacheOverrides = newOverrides)
                }
            }

            // Local File Updater Events
            Event.PickLocalFile -> {
                showFilePicker { path ->
                    if (path != null) {
                        onEvent(Event.LoadLocalFile(path))
                    }
                }
            }
            is Event.LoadLocalFile -> loadLocalFileChapters(event.path)
            is Event.ToggleLocalFileChapterSelection -> {
                _state.update { uiState ->
                    val updatedChapters = uiState.localFileChapters.map {
                        if (it.name == event.chapterName) it.copy(isSelected = event.isSelected) else it
                    }
                    uiState.copy(localFileChapters = updatedChapters)
                }
            }
            Event.StartLocalFileUpdate -> {
                startLocalFileUpdate()
            }
        }
    }

    private fun startDownloadOperation() {
        viewModelScope.launch(Dispatchers.IO) {
            _operationState.value = OperationState.RUNNING
            _state.update { it.copy(progress = 0f, progressStatusText = "Starting operation...") }
            val currentState = _state.value
            val selectedChapters = currentState.fetchedChapters.filter { it.isSelected }

            if (selectedChapters.isEmpty()) {
                Logger.logError("No chapters selected for download.")
                _operationState.value = OperationState.IDLE
                return@launch
            }

            val seriesSlug = currentState.seriesUrl.toSlug()
            val tempSeriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")

            val chaptersToDownload = selectedChapters.filter { !it.isCached || it.url in currentState.chapterCacheOverrides }
            val chaptersToUseFromCache = selectedChapters.filter { it.isCached && it.url !in currentState.chapterCacheOverrides }

            selectedChapters.filter { it.url in currentState.chapterCacheOverrides }.forEach {
                val chapterDir = File(tempSeriesDir, FileUtils.sanitizeFilename(it.title))
                if (chapterDir.exists()) {
                    Logger.logDebug { "Override: Deleting existing cache for ${it.title}" }
                    chapterDir.deleteRecursively()
                }
            }

            val downloadOptions = DownloadOptions(
                seriesUrl = currentState.seriesUrl,
                chaptersToDownload = chaptersToDownload.associate { it.url to it.title },
                cliTitle = currentState.customTitle.ifBlank { null },
                getWorkers = { _state.value.workers },
                format = currentState.outputFormat,
                exclude = emptyList(),
                tempDir = File(platformProvider.getTmpDir()),
                getUserAgents = {
                    val s = _state.value
                    when {
                        s.perWorkerUserAgent -> List(s.workers) { UserAgent.browsers.values.random(Random) }
                        s.userAgentName == "Random" -> listOf(UserAgent.browsers.values.random(Random))
                        else -> listOf(UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first())
                    }
                },
                outputPath = currentState.outputPath,
                operationState = _operationState,
                dryRun = currentState.dryRun,
                onProgressUpdate = { progress, status ->
                    _state.update { it.copy(progress = progress, progressStatusText = status) }
                }
            )

            _state.update { it.copy(activeDownloadOptions = downloadOptions) }

            logOperationSettings(
                downloadOptions,
                chaptersToDownload.size,
                currentState.userAgentName,
                currentState.perWorkerUserAgent
            )

            if (chaptersToUseFromCache.isNotEmpty()){
                Logger.logInfo("Using ${chaptersToUseFromCache.size} chapter(s) from cache.")
            }

            val downloadedFolders = if (chaptersToDownload.isNotEmpty()) {
                downloadService.downloadChaptersOnly(downloadOptions, tempSeriesDir)
            } else {
                Logger.logInfo("No new chapters to download.")
                emptyList()
            }

            if (_operationState.value != OperationState.RUNNING && _operationState.value != OperationState.CANCELLING) {
                if (currentState.deleteCacheOnCancel) {
                    Logger.logInfo("Operation was cancelled. Cleaning up temporary files as requested...")
                    if (tempSeriesDir.exists()) {
                        tempSeriesDir.deleteRecursively()
                    }
                    Logger.logInfo("Cleanup complete.")
                } else {
                    Logger.logInfo("Operation was cancelled. Temporary files have been kept for a future resume.")
                }
            } else if (downloadedFolders != null) {
                val cachedFolders = chaptersToUseFromCache.map {
                    File(tempSeriesDir, FileUtils.sanitizeFilename(it.title))
                }
                val allFolders = (downloadedFolders + cachedFolders).distinctBy { it.absolutePath }

                Logger.logInfo("Download and cache phase complete. Starting file processing...")
                downloadOptions.onProgressUpdate(1f, "Processing...")

                val mangaTitle = currentState.customTitle.ifBlank {
                    downloadOptions.seriesUrl.substringAfterLast("/manga/", "")
                        .substringBefore('/')
                        .replace('-', ' ')
                        .titlecase()
                }

                val outputDir = if (currentState.outputPath.isNotBlank()) File(currentState.outputPath) else File(".")
                if (!outputDir.exists()) outputDir.mkdirs()
                val outputFile = File(outputDir, "${FileUtils.sanitizeFilename(mangaTitle)}.${currentState.outputFormat}")

                val processor = downloadService.processorService
                if (currentState.outputFormat == "cbz") {
                    processor.createCbzFromFolders(mangaTitle, allFolders, outputFile, _operationState)
                } else {
                    processor.createEpubFromFolders(mangaTitle, allFolders, outputFile, _operationState)
                }

                if (_operationState.value == OperationState.CANCELLING) {
                    Logger.logInfo("Operation cancelled during file processing.")
                    if (currentState.deleteCacheOnCancel) {
                        Logger.logInfo("Cleaning up specific chapter folders for this session as requested...")
                        downloadedFolders.forEach { it.deleteRecursively() }
                    } else {
                        Logger.logInfo("Temporary files have been kept for a future resume.")
                    }
                } else {
                    Logger.logInfo("\nDownload and packaging complete: ${outputFile.absolutePath}")
                    Logger.logInfo("Cleaning up temporary files for this session...")
                    var allCleaned = true
                    downloadedFolders.forEach { folder ->
                        if (!folder.deleteRecursively()) {
                            allCleaned = false
                            Logger.logError("Failed to delete temporary chapter folder: ${folder.absolutePath}")
                        }
                    }

                    if (tempSeriesDir.listFiles()?.isEmpty() == true) {
                        Logger.logDebug { "Series temporary directory is now empty, removing it." }
                        tempSeriesDir.delete()
                    }

                    if (allCleaned) {
                        Logger.logInfo("Cleanup successful.")
                    }
                }
            }

            _operationState.value = OperationState.IDLE
            _state.update {
                it.copy(
                    activeDownloadOptions = null,
                    deleteCacheOnCancel = false,
                    chapterCacheOverrides = emptySet(),
                    progress = 0f,
                    progressStatusText = ""
                )
            }
        }
    }

    private fun loadLocalFileChapters(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isAnalyzingFile = true, localFilePath = path, localFileChapters = emptyList()) }
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                Logger.logError("Selected path is not a valid file: $path")
                _state.update { it.copy(isAnalyzingFile = false, localFilePath = "") }
                return@launch
            }

            val chapters = downloadService.processorService.getChaptersFromFile(file)
            if (chapters.isEmpty()) {
                Logger.logInfo("No chapters could be identified in the file. It might be an unsupported format.")
            }

            _state.update { uiState ->
                uiState.copy(
                    isAnalyzingFile = false,
                    localFileChapters = chapters.map { ChapterInFile(name = it, isSelected = true) }
                )
            }
        }
    }

    private fun startLocalFileUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            _operationState.value = OperationState.RUNNING
            _state.update { it.copy(progress = 0f, progressStatusText = "Starting update...") }
            val s = _state.value
            val inputFile = File(s.localFilePath)
            val chaptersToKeep = s.localFileChapters.filter { it.isSelected }.map { it.name }

            if (chaptersToKeep.isEmpty()) {
                Logger.logError("No chapters selected to keep. Aborting update.")
                _operationState.value = OperationState.IDLE
                return@launch
            }

            Logger.logInfo("--- Starting Local File Update ---")
            Logger.logInfo("Input file: ${inputFile.name}")
            Logger.logInfo("Keeping ${chaptersToKeep.size} of ${s.localFileChapters.size} chapters.")

            _state.update { it.copy(progress = 0.5f, progressStatusText = "Re-packaging file...") }

            downloadService.processorService.updateLocalFile(
                inputFile = inputFile,
                chaptersToKeep = chaptersToKeep,
                onProgressUpdate = { progress, status ->
                    _state.update { it.copy(progress = progress, progressStatusText = status) }
                }
            )

            Logger.logInfo("--- File Update Complete ---")
            _operationState.value = OperationState.IDLE
            _state.update {
                it.copy(localFilePath = "", localFileChapters = emptyList(), progress = 0f, progressStatusText = "")
            }
        }
    }

    private fun fetchChapters() {
        if (_state.value.seriesUrl.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isFetchingChapters = true, fetchedChapters = emptyList(), chapterCacheOverrides = emptySet()) }
            val seriesSlug = _state.value.seriesUrl.toSlug()
            val cachedChapterNames = cacheService.getCachedChapterNamesForSeries(seriesSlug)

            val userAgent = UserAgent.browsers[_state.value.userAgentName] ?: UserAgent.browsers.values.first()
            val client = createHttpClient("")
            Logger.logInfo("Fetching chapter list for: ${_state.value.seriesUrl}")
            val chapters = scraperService.findChapterUrlsAndTitles(client, _state.value.seriesUrl, userAgent)
            if (chapters.isNotEmpty()) {
                val sortedChapters = chapters.sortedWith(ChapterPairComparator)

                _state.update {
                    it.copy(
                        isFetchingChapters = false,
                        fetchedChapters = sortedChapters.map { (url, title) ->
                            Chapter(
                                url = url,
                                title = title,
                                isCached = FileUtils.sanitizeFilename(title) in cachedChapterNames
                            )
                        },
                        showChapterDialog = true
                    )
                }
            } else {
                Logger.logError("Could not find any chapters at the provided URL.")
                _state.update { it.copy(isFetchingChapters = false) }
            }
            client.close()
        }
    }

    private fun updateChapterRange(start: Int, end: Int, action: RangeAction) {
        if (start > end || start < 1 || end > _state.value.fetchedChapters.size) {
            Logger.logError("Invalid chapter range: $start-$end")
            return
        }
        _state.update {
            val updatedChapters = it.fetchedChapters.mapIndexed { index, chapter ->
                if (index + 1 in start..end) {
                    when (action) {
                        RangeAction.SELECT -> chapter.copy(isSelected = true)
                        RangeAction.DESELECT -> chapter.copy(isSelected = false)
                        RangeAction.TOGGLE -> chapter.copy(isSelected = !chapter.isSelected)
                    }
                } else {
                    chapter
                }
            }
            it.copy(fetchedChapters = updatedChapters)
        }
    }

    private fun setAllChaptersSelected(isSelected: Boolean) {
        _state.update {
            val updatedChapters = it.fetchedChapters.map { chapter -> chapter.copy(isSelected = isSelected) }
            it.copy(fetchedChapters = updatedChapters)
        }
    }
}
