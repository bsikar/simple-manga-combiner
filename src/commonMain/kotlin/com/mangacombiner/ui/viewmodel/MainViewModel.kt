package com.mangacombiner.ui.viewmodel

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.model.AppSettings
import com.mangacombiner.service.CacheService
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.SlugUtils
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.logOperationSettings
import com.mangacombiner.util.naturalSortComparator
import com.mangacombiner.util.titlecase
import com.mangacombiner.util.toSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.io.path.nameWithoutExtension
import kotlin.random.Random

enum class Screen {
    DOWNLOAD,
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

data class CacheSortState(val criteria: SortCriteria, val direction: SortDirection)

sealed class FilePickerRequest {
    data object OpenFile : FilePickerRequest()
    data class OpenFolder(val forPath: PathType) : FilePickerRequest()

    enum class PathType {
        DEFAULT_OUTPUT, CUSTOM_OUTPUT
    }
}

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
    val isAnalyzingFile: Boolean = false,
    val fetchedChapters: List<Chapter> = emptyList(),
    val localChaptersForSync: Map<String, String> = emptyMap(),
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
    val systemLightTheme: AppTheme = AppTheme.LIGHT,
    val systemDarkTheme: AppTheme = AppTheme.DARK,
    val showRestoreDefaultsDialog: Boolean = false,
    val outputFileExists: Boolean = false,
    val showOverwriteConfirmationDialog: Boolean = false,
    val showAboutDialog: Boolean = false,
)

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
    private val settingsRepository: SettingsRepository,
    private val fileMover: FileMover
) : PlatformViewModel() {

    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState>

    private val _operationState = MutableStateFlow(OperationState.IDLE)

    private val _filePickerRequest = MutableSharedFlow<FilePickerRequest>()
    val filePickerRequest = _filePickerRequest.asSharedFlow()

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
                cachePath = platformProvider.getTmpDir(),
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
        data class UpdateChapterSource(val chapterUrl: String, val source: ChapterSource?) : Event()
        data class ToggleChapterSelection(val chapterUrl: String, val select: Boolean) : Event()
        data class ToggleChapterRedownload(val chapterUrl: String) : Event()
        data class UpdateChapterRange(val start: Int, val end: Int, val action: RangeAction) : Event()
        data class ToggleCacheItemForDeletion(val path: String) : Event()
        data class UpdateCachedChapterRange(val seriesPath: String, val start: Int, val end: Int, val action: RangeAction) : Event()
        data class SelectAllCachedChapters(val seriesPath: String, val select: Boolean) : Event()
        data class ToggleDeleteCacheOnCancel(val delete: Boolean) : Event()
        data class UpdateDefaultOutputLocation(val location: String) : Event()
        data class SetCacheSort(val seriesPath: String, val sortState: CacheSortState?) : Event()
        data class ToggleCacheSeries(val seriesPath: String) : Event()
        data class UpdateFontSizePreset(val preset: String) : Event()
        data class UpdateSystemLightTheme(val theme: AppTheme) : Event()
        data class UpdateSystemDarkTheme(val theme: AppTheme) : Event()
        data class ToggleAboutDialog(val show: Boolean) : Event()
        data class ContinueFromCache(val url: String) : Event()

        object PickCustomDefaultPath : Event()
        object PickOutputPath : Event()
        object PickLocalFile : Event()
        object ToggleLogAutoscroll : Event()
        object CopyLogsToClipboard : Event()
        object ClearLogs : Event()
        object FetchChapters : Event()
        object ConfirmChapterSelection : Event()
        object CancelChapterSelection : Event()
        object SelectAllChapters : Event()
        object DeselectAllChapters : Event()
        object UseAllLocal : Event()
        object IgnoreAllLocal : Event()
        object RedownloadAllLocal : Event()
        object UseAllCached : Event()
        object IgnoreAllCached : Event()
        object RedownloadAllCached : Event()
        object RequestStartOperation : Event()
        object ConfirmOverwrite : Event()
        object CancelOverwrite : Event()
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
        object OpenSettingsLocation : Event()
        object ZoomIn : Event()
        object ZoomOut : Event()
        object ZoomReset : Event()
        object RequestRestoreDefaults : Event()
        object ConfirmRestoreDefaults : Event()
        object CancelRestoreDefaults : Event()
        object ClearDownloadInputs : Event()
    }

    fun onFileSelected(path: String) {
        startFromFile(path)
    }

    fun onFolderSelected(path: String, type: FilePickerRequest.PathType) {
        when (type) {
            FilePickerRequest.PathType.DEFAULT_OUTPUT -> {
                _state.update { it.copy(outputPath = path) }
            }
            FilePickerRequest.PathType.CUSTOM_OUTPUT -> {
                _state.update { it.copy(customDefaultOutputPath = path, outputPath = path) }
            }
        }
        checkOutputFileExistence()
    }

    private fun getChapterDefaultSource(chapter: Chapter): ChapterSource {
        return when {
            chapter.availableSources.contains(ChapterSource.LOCAL) -> ChapterSource.LOCAL
            chapter.availableSources.contains(ChapterSource.CACHE) -> ChapterSource.CACHE
            else -> ChapterSource.WEB
        }
    }

    fun onEvent(event: Event) {
        when (event) {
            is Event.ContinueFromCache -> {
                _state.update {
                    it.copy(
                        currentScreen = Screen.DOWNLOAD,
                        seriesUrl = event.url,
                        customTitle = event.url.substringAfterLast("/manga/", "")
                            .substringBefore('/')
                            .replace('-', ' ')
                            .titlecase(),
                        sourceFilePath = null,
                        fetchedChapters = emptyList(),
                        localChaptersForSync = emptyMap()
                    )
                }
                onEvent(Event.FetchChapters)
            }
            is Event.UpdateDefaultOutputLocation -> {
                val newPath = when (event.location) {
                    "Downloads" -> platformProvider.getUserDownloadsDir()
                    "Documents" -> platformProvider.getUserDocumentsDir()
                    "Desktop" -> platformProvider.getUserDesktopDir()
                    "Custom" -> _state.value.customDefaultOutputPath
                    else -> ""
                } ?: ""
                _state.update { it.copy(defaultOutputLocation = event.location, outputPath = newPath) }
                checkOutputFileExistence()
            }
            Event.PickCustomDefaultPath -> viewModelScope.launch {
                _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.CUSTOM_OUTPUT))
            }
            Event.PickOutputPath -> viewModelScope.launch {
                _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.DEFAULT_OUTPUT))
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
            is Event.UpdateOutputPath -> {
                _state.update { it.copy(outputPath = event.path) }
                checkOutputFileExistence()
            }
            is Event.Navigate -> {
                _state.update { it.copy(currentScreen = event.screen) }
                if (event.screen == Screen.CACHE_VIEWER) {
                    onEvent(Event.RefreshCacheView)
                }
            }
            is Event.UpdateUrl -> {
                val newUrl = event.url
                val newTitle = if (newUrl.isNotBlank() && newUrl.contains("/manga/")) {
                    newUrl.substringAfterLast("/manga/", "")
                        .substringBefore('/')
                        .replace('-', ' ')
                        .titlecase()
                } else {
                    ""
                }
                _state.update {
                    it.copy(
                        seriesUrl = newUrl,
                        customTitle = newTitle,
                        sourceFilePath = null,
                        localChaptersForSync = emptyMap(),
                        fetchedChapters = emptyList()
                    )
                }
                checkOutputFileExistence()
            }
            is Event.UpdateCustomTitle -> {
                _state.update { it.copy(customTitle = event.title) }
                checkOutputFileExistence()
            }
            is Event.UpdateWorkers -> _state.update { it.copy(workers = event.count.coerceIn(1, 16)) }
            is Event.UpdateFormat -> {
                _state.update { it.copy(outputFormat = event.format) }
                checkOutputFileExistence()
            }
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
                        if (chapter.url == event.chapterUrl) {
                            val newSource = if (event.select) getChapterDefaultSource(chapter) else null
                            chapter.copy(selectedSource = newSource)
                        } else {
                            chapter
                        }
                    }
                    it.copy(fetchedChapters = updatedChapters)
                }
            }
            is Event.ToggleChapterRedownload -> {
                _state.update {
                    val updatedChapters = it.fetchedChapters.map { chapter ->
                        if (chapter.url == event.chapterUrl) {
                            val newSource = if (chapter.selectedSource == ChapterSource.WEB) {
                                getChapterDefaultSource(chapter)
                            } else {
                                ChapterSource.WEB
                            }
                            chapter.copy(selectedSource = newSource)
                        } else {
                            chapter
                        }
                    }
                    it.copy(fetchedChapters = updatedChapters)
                }
            }
            is Event.UpdateChapterSource -> {
                _state.update {
                    val updated = it.fetchedChapters.map { ch ->
                        if (ch.url == event.chapterUrl) ch.copy(selectedSource = event.source) else ch
                    }
                    it.copy(fetchedChapters = updated)
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
                _state.update { it.copy(showChapterDialog = false, fetchedChapters = emptyList()) }
            }
            Event.SelectAllChapters -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch -> ch.copy(selectedSource = getChapterDefaultSource(ch)) })
            }
            Event.DeselectAllChapters -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch -> ch.copy(selectedSource = null) })
            }
            Event.UseAllLocal -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
                    if (ch.availableSources.contains(ChapterSource.LOCAL)) ch.copy(selectedSource = ChapterSource.LOCAL) else ch
                })
            }
            Event.IgnoreAllLocal -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
                    if (ch.availableSources.contains(ChapterSource.LOCAL)) ch.copy(selectedSource = null) else ch
                })
            }
            Event.RedownloadAllLocal -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
                    if (ch.availableSources.contains(ChapterSource.LOCAL)) ch.copy(selectedSource = ChapterSource.WEB) else ch
                })
            }
            Event.UseAllCached -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
                    if (ch.availableSources.contains(ChapterSource.CACHE)) ch.copy(selectedSource = ChapterSource.CACHE) else ch
                })
            }
            Event.IgnoreAllCached -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
                    if (ch.availableSources.contains(ChapterSource.CACHE)) ch.copy(selectedSource = null) else ch
                })
            }
            Event.RedownloadAllCached -> _state.update {
                it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
                    if (ch.availableSources.contains(ChapterSource.CACHE)) ch.copy(selectedSource = ChapterSource.WEB) else ch
                })
            }
            Event.RequestStartOperation -> {
                val s = _state.value
                if (s.sourceFilePath != null) {
                    _state.update { it.copy(showOverwriteConfirmationDialog = true) }
                } else {
                    startOperation()
                }
            }
            Event.ConfirmOverwrite -> {
                _state.update { it.copy(showOverwriteConfirmationDialog = false) }
                startOperation()
            }
            Event.CancelOverwrite -> {
                _state.update { it.copy(showOverwriteConfirmationDialog = false) }
            }
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
            Event.PickLocalFile -> viewModelScope.launch {
                _filePickerRequest.emit(FilePickerRequest.OpenFile)
            }
            Event.ClearDownloadInputs -> {
                _state.update {
                    it.copy(
                        seriesUrl = "",
                        customTitle = "",
                        sourceFilePath = null,
                        fetchedChapters = emptyList(),
                        localChaptersForSync = emptyMap(),
                        outputFileExists = false
                    )
                }
            }
            is Event.ToggleAboutDialog -> {
                _state.update { it.copy(showAboutDialog = event.show) }
            }
        }
    }

    private fun startOperation() {
        viewModelScope.launch(Dispatchers.IO) {
            _operationState.value = OperationState.RUNNING
            _state.update { it.copy(progress = 0f, progressStatusText = "Starting operation...") }
            val s = _state.value

            val tempDir = File(platformProvider.getTmpDir())
            val tempUpdateDir = File(tempDir, "manga-update-${System.currentTimeMillis()}").apply { mkdirs() }

            try {
                if (s.seriesUrl.isNotBlank()) {
                    val seriesSlug = s.seriesUrl.toSlug()
                    val tempSeriesDir = File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }
                    val urlFile = File(tempSeriesDir, "url.txt")
                    if (!urlFile.exists()) {
                        try {
                            urlFile.writeText(s.seriesUrl)
                        } catch (e: Exception) {
                            Logger.logError("Failed to save series URL to cache", e)
                        }
                    }
                }

                val selectedChapters = s.fetchedChapters.filter { it.selectedSource != null }
                if (selectedChapters.isEmpty()) {
                    Logger.logError("No chapters selected for operation. Aborting.")
                    _operationState.value = OperationState.IDLE
                    return@launch
                }

                val chaptersToExtract = selectedChapters.filter { it.selectedSource == ChapterSource.LOCAL }
                val chaptersFromCache = selectedChapters.filter { it.selectedSource == ChapterSource.CACHE }
                val chaptersToDownload = selectedChapters.filter { it.selectedSource == ChapterSource.WEB }

                val downloadOptions = createDownloadOptions(s, s.seriesUrl, chaptersToDownload)
                _state.update { it.copy(activeDownloadOptions = downloadOptions) }
                logOperationSettings(
                    downloadOptions,
                    chaptersToDownload.size,
                    s.userAgentName,
                    s.perWorkerUserAgent,
                    localCount = chaptersToExtract.size,
                    cacheCount = chaptersFromCache.size
                )

                val allChapterFolders = mutableListOf<File>()

                if (s.sourceFilePath != null && chaptersToExtract.isNotEmpty()) {
                    _state.update { it.copy(progress = 0.1f, progressStatusText = "Extracting local chapters...") }
                    val extracted = downloadService.processorService.extractChaptersToDirectory(
                        File(s.sourceFilePath),
                        chaptersToExtract.mapNotNull { it.localSlug },
                        tempUpdateDir
                    )
                    allChapterFolders.addAll(extracted)
                }

                val seriesSlug = s.seriesUrl.toSlug()
                val tempSeriesDir = File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }

                if (chaptersToDownload.isNotEmpty()) {
                    val downloaded = downloadService.downloadChaptersOnly(downloadOptions, tempSeriesDir)
                    if (downloaded != null) {
                        allChapterFolders.addAll(downloaded)
                    } else if (_operationState.value != OperationState.RUNNING) {
                        return@launch
                    }
                }

                if (chaptersFromCache.isNotEmpty()) {
                    chaptersFromCache.forEach {
                        allChapterFolders.add(File(tempSeriesDir, FileUtils.sanitizeFilename(it.title)))
                    }
                }

                _state.update { it.copy(progress = 0.8f, progressStatusText = "Repackaging file...") }
                val mangaTitle = s.customTitle.ifBlank { s.seriesUrl.substringAfterLast("/manga/", "").substringBefore('/').replace('-', ' ').titlecase() }

                val tempOutputFile = File(tempDir, "${FileUtils.sanitizeFilename(mangaTitle)}-temp.${s.outputFormat}")
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }

                if (s.outputFormat == "cbz") {
                    downloadService.processorService.createCbzFromFolders(mangaTitle, allChapterFolders, tempOutputFile, s.seriesUrl, _operationState)
                } else {
                    downloadService.processorService.createEpubFromFolders(mangaTitle, allChapterFolders, tempOutputFile, s.seriesUrl, _operationState)
                }

                if (tempOutputFile.exists()) {
                    val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${s.outputFormat}"
                    val finalPath = fileMover.moveToFinalDestination(tempOutputFile, s.outputPath, finalFileName)

                    if (finalPath.isNotBlank()) {
                        Logger.logInfo("Successfully created file: $finalPath")
                    } else {
                        Logger.logError("Failed to move temporary file to the final destination. It can be found at: ${tempOutputFile.absolutePath}")
                    }
                } else if (_operationState.value != OperationState.CANCELLING) {
                    Logger.logError("Failed to create the output file.")
                }

            } finally {
                tempUpdateDir.deleteRecursively()
                _operationState.value = OperationState.IDLE
                _state.update {
                    it.copy(
                        activeDownloadOptions = null,
                        deleteCacheOnCancel = false,
                        progress = 0f,
                        progressStatusText = "",
                        sourceFilePath = null,
                        localChaptersForSync = emptyMap(),
                        fetchedChapters = emptyList(),
                        seriesUrl = "",
                        customTitle = ""
                    )
                }
                Logger.logInfo("--- Operation Complete ---")
            }
        }
    }

    private fun startFromFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                Logger.logError("Selected path is not a valid file: $path")
                return@launch
            }

            _state.update { it.copy(isAnalyzingFile = true) }

            val (chapterSlugs, url) = downloadService.processorService.getChaptersAndInfoFromFile(file)

            _state.update {
                it.copy(
                    isAnalyzingFile = false,
                    seriesUrl = url ?: "",
                    customTitle = file.nameWithoutExtension,
                    outputFormat = file.extension,
                    outputPath = file.parent ?: it.outputPath,
                    sourceFilePath = file.path,
                    localChaptersForSync = chapterSlugs.associateBy { slug -> SlugUtils.toComparableKey(slug) },
                    fetchedChapters = emptyList()
                )
            }
            checkOutputFileExistence()
        }
    }

    private fun fetchChapters() {
        val url = _state.value.seriesUrl
        if (url.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isFetchingChapters = true) }

            val seriesSlug = url.toSlug()
            val cachedChapterNames = cacheService.getCachedChapterNamesForSeries(seriesSlug)
            val localChapterMap = _state.value.localChaptersForSync

            val userAgent = UserAgent.browsers[_state.value.userAgentName] ?: UserAgent.browsers.values.first()
            val client = createHttpClient("")
            Logger.logInfo("Fetching chapter list for: $url")
            val chapters = scraperService.findChapterUrlsAndTitles(client, url, userAgent)
            client.close()

            if (chapters.isNotEmpty()) {
                val allChapters = chapters.map { (chapUrl, title) ->
                    val sanitizedTitle = FileUtils.sanitizeFilename(title)
                    val comparableKey = SlugUtils.toComparableKey(title)

                    val originalLocalSlug = localChapterMap[comparableKey]
                    val isLocal = originalLocalSlug != null
                    val isCached = sanitizedTitle in cachedChapterNames

                    val sources = mutableSetOf(ChapterSource.WEB)
                    if (isLocal) sources.add(ChapterSource.LOCAL)
                    if (isCached) sources.add(ChapterSource.CACHE)

                    val defaultSource = when {
                        isLocal -> ChapterSource.LOCAL
                        isCached -> ChapterSource.CACHE
                        else -> ChapterSource.WEB
                    }

                    Chapter(
                        url = chapUrl,
                        title = title,
                        availableSources = sources,
                        selectedSource = defaultSource,
                        localSlug = originalLocalSlug
                    )
                }.sortedWith(compareBy(naturalSortComparator) { it.title })

                _state.update {
                    it.copy(
                        isFetchingChapters = false,
                        fetchedChapters = allChapters,
                        showChapterDialog = true
                    )
                }
            } else {
                Logger.logError("Could not find any chapters at the provided URL.")
                _state.update { it.copy(isFetchingChapters = false) }
            }
        }
    }

    private fun createDownloadOptions(s: UiState, seriesUrl: String, chaptersToDownload: List<Chapter>): DownloadOptions {
        return DownloadOptions(
            seriesUrl = seriesUrl,
            chaptersToDownload = chaptersToDownload.associate { it.url to it.title },
            cliTitle = null,
            getWorkers = { s.workers },
            format = s.outputFormat,
            exclude = emptyList(),
            tempDir = File(platformProvider.getTmpDir()),
            getUserAgents = {
                when {
                    s.perWorkerUserAgent -> List(s.workers) { UserAgent.browsers.values.random(Random) }
                    s.userAgentName == "Random" -> listOf(UserAgent.browsers.values.random(Random))
                    else -> listOf(UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first())
                }
            },
            outputPath = "",
            operationState = _operationState,
            dryRun = s.dryRun,
            onProgressUpdate = { progress, status ->
                _state.update { it.copy(progress = progress, progressStatusText = status) }
            }
        )
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
                        RangeAction.SELECT -> chapter.copy(selectedSource = getChapterDefaultSource(chapter))
                        RangeAction.DESELECT -> chapter.copy(selectedSource = null)
                        RangeAction.TOGGLE -> chapter.copy(
                            selectedSource = if (chapter.selectedSource == null) getChapterDefaultSource(chapter) else null
                        )
                    }
                } else {
                    chapter
                }
            }
            it.copy(fetchedChapters = updatedChapters)
        }
    }

    private fun checkOutputFileExistence() {
        val s = _state.value
        if (s.customTitle.isBlank() || s.outputPath.isBlank() || s.outputPath.startsWith("content://")) {
            _state.update { it.copy(outputFileExists = false) }
            return
        }

        val outputFile = File(File(s.outputPath), "${FileUtils.sanitizeFilename(s.customTitle)}.${s.outputFormat}")

        if (outputFile.exists()) {
            _state.update { it.copy(outputFileExists = true) }
            if (s.sourceFilePath == null) {
                analyzeLocalFile(outputFile)
            }
        } else {
            _state.update { it.copy(outputFileExists = false) }
            if (s.sourceFilePath == outputFile.path) {
                clearLocalFileInfo()
            }
        }
    }

    private fun analyzeLocalFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!file.exists() || !file.isFile) {
                Logger.logError("Path is not a valid file for analysis: ${file.path}")
                return@launch
            }

            _state.update { it.copy(isAnalyzingFile = true) }

            val (chapterSlugs, url) = downloadService.processorService.getChaptersAndInfoFromFile(file)

            _state.update { currentState ->
                if (chapterSlugs.isEmpty() && url == null) {
                    Logger.logInfo("No chapters or URL could be identified in the file: ${file.name}. It might be an unsupported format or not created by this app.")
                    currentState.copy(
                        isAnalyzingFile = false,
                        sourceFilePath = null,
                        localChaptersForSync = emptyMap()
                    )
                } else {
                    if (url != null) Logger.logInfo("Found embedded series URL in ${file.name}: $url")
                    currentState.copy(
                        isAnalyzingFile = false,
                        seriesUrl = currentState.seriesUrl.ifBlank { url ?: "" },
                        sourceFilePath = file.path,
                        localChaptersForSync = chapterSlugs.associateBy { SlugUtils.toComparableKey(it) }
                    )
                }
            }
        }
    }

    private fun clearLocalFileInfo() {
        _state.update {
            it.copy(
                sourceFilePath = null,
                localChaptersForSync = emptyMap()
            )
        }
    }
}
