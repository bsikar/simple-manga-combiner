package com.mangacombiner.ui.viewmodel

import com.mangacombiner.service.CacheService
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.logOperationSettings
import com.mangacombiner.util.showFolderPicker
import com.mangacombiner.util.titlecase
import com.mangacombiner.util.toSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

enum class Screen {
    DOWNLOAD,
    SETTINGS,
    CACHE_VIEWER
}

data class Chapter(
    val url: String,
    val title: String,
    var isSelected: Boolean = true
)

enum class RangeAction {
    SELECT, DESELECT, TOGGLE
}

data class UiState(
    val currentScreen: Screen = Screen.DOWNLOAD,
    val theme: AppTheme = AppTheme.DARK,
    val seriesUrl: String = "",
    val customTitle: String = "",
    val outputPath: String = "",
    val defaultOutputLocation: String = "Downloads", // "Downloads", "Documents", "Desktop", "Custom"
    val customDefaultOutputPath: String = "",
    val workers: Int = 4,
    val outputFormat: String = "epub",
    val userAgentName: String = "Chrome (Windows)",
    val perWorkerUserAgent: Boolean = false,
    val debugLog: Boolean = false,
    val dryRun: Boolean = false,
    val operationState: OperationState = OperationState.IDLE,
    val isFetchingChapters: Boolean = false,
    val fetchedChapters: List<Chapter> = emptyList(),
    val showChapterDialog: Boolean = false,
    val showClearCacheDialog: Boolean = false,
    val showCancelDialog: Boolean = false,
    val deleteCacheOnCancel: Boolean = false,
    val showDeleteCacheConfirmationDialog: Boolean = false,
    val cacheContents: List<CachedSeries> = emptyList(),
    val cacheItemsToDelete: Set<String> = emptySet(),
    val logAutoscrollEnabled: Boolean = true,
    val activeDownloadOptions: DownloadOptions? = null
)

class MainViewModel(
    private val downloadService: DownloadService,
    private val scraperService: ScraperService,
    private val clipboardManager: ClipboardManager,
    private val platformProvider: PlatformProvider,
    private val cacheService: CacheService
) : PlatformViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _operationState = MutableStateFlow(OperationState.IDLE)

    private val _logs = MutableStateFlow(listOf("Welcome to Manga Combiner!"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        Logger.addListener { logMessage ->
            _logs.update { it + logMessage }
        }
        viewModelScope.launch {
            _operationState.collect {
                _state.update { uiState -> uiState.copy(operationState = it) }
            }
        }
        val defaultDownloadsPath = platformProvider.getUserDownloadsDir() ?: ""
        _state.update { it.copy(outputPath = defaultDownloadsPath, customDefaultOutputPath = defaultDownloadsPath) }
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
            is Event.UpdateOutputPath -> _state.update { it.copy(outputPath = event.path) }
            is Event.Navigate -> {
                _state.update { it.copy(currentScreen = event.screen) }
                if (event.screen == Screen.CACHE_VIEWER) {
                    onEvent(Event.RefreshCacheView)
                }
            }
            is Event.UpdateUrl -> _state.update { it.copy(seriesUrl = event.url) }
            is Event.UpdateCustomTitle -> _state.update { it.copy(customTitle = event.title) }
            is Event.UpdateWorkers -> _state.update { it.copy(workers = event.count.coerceIn(1, 32)) }
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
            Event.ConfirmChapterSelection -> _state.update { it.copy(showChapterDialog = false) }
            Event.CancelChapterSelection -> _state.update { it.copy(showChapterDialog = false, fetchedChapters = emptyList()) }
            Event.SelectAllChapters -> setAllChaptersSelected(true)
            Event.DeselectAllChapters -> setAllChaptersSelected(false)
            Event.StartOperation -> startDownloadOperation()
            Event.PauseOperation -> _operationState.value = OperationState.PAUSED
            Event.ResumeOperation -> {
                logOperationSettings(
                    _state.value.activeDownloadOptions!!,
                    _state.value.activeDownloadOptions!!.chaptersToDownload.size,
                    _state.value,
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
                _state.update {
                    val newSet = it.cacheItemsToDelete.toMutableSet()
                    if (newSet.contains(event.path)) newSet.remove(event.path) else newSet.add(event.path)
                    it.copy(cacheItemsToDelete = newSet)
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
                    val chapterPaths = series.chapters.map { it.path }
                    val currentSelection = uiState.cacheItemsToDelete.toMutableSet()
                    if (event.select) {
                        currentSelection.addAll(chapterPaths)
                    } else {
                        currentSelection.removeAll(chapterPaths.toSet())
                    }
                    uiState.copy(cacheItemsToDelete = currentSelection)
                }
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
        }
    }

    private fun startDownloadOperation() {
        viewModelScope.launch(Dispatchers.IO) {
            _operationState.value = OperationState.RUNNING
            var currentState = _state.value
            val chaptersToDownload = currentState.fetchedChapters.filter { it.isSelected }

            if (chaptersToDownload.isEmpty()) {
                Logger.logError("No chapters selected for download.")
                _operationState.value = OperationState.IDLE
                return@launch
            }

            val initialOptions = DownloadOptions(
                seriesUrl = currentState.seriesUrl,
                chaptersToDownload = chaptersToDownload.associate { it.url to it.title },
                cliTitle = currentState.customTitle.ifBlank { null },
                imageWorkers = currentState.workers,
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
                dryRun = currentState.dryRun
            )
            _state.update { it.copy(activeDownloadOptions = initialOptions) }
            logOperationSettings(initialOptions, chaptersToDownload.size, currentState)

            val seriesSlug = initialOptions.seriesUrl.toSlug()
            val downloadDir = File(initialOptions.tempDir, "manga-dl-$seriesSlug")
            val downloadedFolders = downloadService.downloadChaptersOnly(initialOptions, downloadDir)

            if (_operationState.value != OperationState.RUNNING) {
                // This block handles cancellation during the download phase
                if (_state.value.deleteCacheOnCancel) {
                    Logger.logInfo("Operation was cancelled. Cleaning up temporary files as requested...")
                    if (downloadDir.exists()) {
                        downloadDir.deleteRecursively()
                    }
                    Logger.logInfo("Cleanup complete.")
                } else {
                    Logger.logInfo("Operation was cancelled. Temporary files have been kept for a future resume.")
                }
            } else if (downloadedFolders != null) {
                Logger.logInfo("Download phase complete. Starting file processing...")
                currentState = _state.value

                val mangaTitle = currentState.customTitle.ifBlank {
                    initialOptions.seriesUrl.substringAfterLast("/manga/", "")
                        .substringBefore('/')
                        .replace('-', ' ')
                        .titlecase()
                }

                val outputDir = if (currentState.outputPath.isNotBlank()) File(currentState.outputPath) else File(".")
                if (!outputDir.exists()) outputDir.mkdirs()
                val outputFile = File(outputDir, "${FileUtils.sanitizeFilename(mangaTitle)}.${currentState.outputFormat}")

                val processor = downloadService.processorService
                if (currentState.outputFormat == "cbz") {
                    processor.createCbzFromFolders(mangaTitle, downloadedFolders, outputFile, _operationState)
                } else {
                    processor.createEpubFromFolders(mangaTitle, downloadedFolders, outputFile, _operationState)
                }

                if (_operationState.value == OperationState.CANCELLING) {
                    Logger.logInfo("Operation cancelled during file processing.")
                    if (_state.value.deleteCacheOnCancel) {
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

                    if (downloadDir.listFiles()?.isEmpty() == true) {
                        Logger.logDebug { "Series temporary directory is now empty, removing it." }
                        downloadDir.delete()
                    }

                    if (allCleaned) {
                        Logger.logInfo("Cleanup successful.")
                    }
                }
            }

            _operationState.value = OperationState.IDLE
            _state.update { it.copy(activeDownloadOptions = null, deleteCacheOnCancel = false) }
        }
    }

    private fun fetchChapters() {
        if (_state.value.seriesUrl.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isFetchingChapters = true) }
            val userAgent = UserAgent.browsers[_state.value.userAgentName] ?: UserAgent.browsers.values.first()
            val client = createHttpClient(userAgent)
            Logger.logInfo("Fetching chapter list for: ${_state.value.seriesUrl}")
            val chapters = scraperService.findChapterUrlsAndTitles(client, _state.value.seriesUrl)
            if (chapters.isNotEmpty()) {
                _state.update {
                    it.copy(
                        isFetchingChapters = false,
                        fetchedChapters = chapters.map { (url, title) -> Chapter(url, title) },
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
