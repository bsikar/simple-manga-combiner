package com.mangacombiner.ui.viewmodel

import com.mangacombiner.service.CacheService
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.Logger
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.getTmpDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

enum class Screen {
    DOWNLOAD,
    SYNC,
    SETTINGS
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
    val workers: Int = 4,
    val outputFormat: String = "epub",
    val userAgentName: String = "Chrome (Windows)",
    val perWorkerUserAgent: Boolean = false,
    val debugLog: Boolean = false,
    val dryRun: Boolean = false,
    val isBusy: Boolean = false,
    val isFetchingChapters: Boolean = false,
    val fetchedChapters: List<Chapter> = emptyList(),
    val showChapterDialog: Boolean = false,
    val localFilePath: String = "",
    val showClearCacheDialog: Boolean = false
)

class MainViewModel(
    private val downloadService: DownloadService,
    private val scraperService: ScraperService,
    private val clipboardManager: ClipboardManager
) : PlatformViewModel() {
    private val viewModelScope = CoroutineScope(Dispatchers.Default)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _logs = MutableStateFlow(listOf("Welcome to Manga Combiner!"))
    val logs = _logs.asStateFlow()

    init {
        Logger.addListener { logMessage ->
            _logs.update { it + logMessage }
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
        object CopyLogsToClipboard : Event()
        object ClearLogs : Event()
        object FetchChapters : Event()
        object ConfirmChapterSelection : Event()
        object CancelChapterSelection : Event()
        object SelectAllChapters : Event()
        object DeselectAllChapters : Event()
        object StartOperation : Event()
        object RequestClearCache : Event()
        object ConfirmClearCache : Event()
        object CancelClearCache : Event()
    }

    fun onEvent(event: Event) {
        when (event) {
            is Event.Navigate -> _state.update { it.copy(currentScreen = event.screen) }
            is Event.UpdateUrl -> _state.update { it.copy(seriesUrl = event.url) }
            is Event.UpdateCustomTitle -> _state.update { it.copy(customTitle = event.title) }
            is Event.UpdateOutputPath -> _state.update { it.copy(outputPath = event.path) }
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
            Event.RequestClearCache -> _state.update { it.copy(showClearCacheDialog = true) }
            Event.CancelClearCache -> _state.update { it.copy(showClearCacheDialog = false) }
            Event.ConfirmClearCache -> {
                _state.update { it.copy(showClearCacheDialog = false) }
                viewModelScope.launch(Dispatchers.IO) {
                    CacheService.clearAppCache()
                }
            }
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

    private fun startDownloadOperation() {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            val currentState = _state.value
            val chaptersToDownload = currentState.fetchedChapters.filter { it.isSelected }

            if (chaptersToDownload.isEmpty()) {
                Logger.logError("No chapters selected for download.")
                _state.update { it.copy(isBusy = false) }
                return@launch
            }

            val userAgents = when {
                currentState.perWorkerUserAgent -> {
                    List(currentState.workers) { UserAgent.browsers.values.random(Random) }
                }
                currentState.userAgentName == "Random" -> {
                    listOf(UserAgent.browsers.values.random(Random))
                }
                else -> {
                    listOf(UserAgent.browsers[currentState.userAgentName] ?: UserAgent.browsers.values.first())
                }
            }

            val downloadOptions = DownloadOptions(
                seriesUrl = currentState.seriesUrl,
                chaptersToDownload = chaptersToDownload.associate { it.url to it.title },
                cliTitle = currentState.customTitle.ifBlank { null },
                imageWorkers = currentState.workers,
                format = currentState.outputFormat,
                exclude = emptyList(),
                tempDir = File(getTmpDir()),
                userAgents = userAgents,
                outputPath = currentState.outputPath,
                dryRun = currentState.dryRun
            )

            // Log operation details before starting
            Logger.logInfo("--- Starting New Download Operation ---")
            if (currentState.dryRun) {
                Logger.logInfo("Mode:               Dry Run (no files will be downloaded or created)")
            }
            Logger.logInfo("Series URL:         ${downloadOptions.seriesUrl}")
            if (!downloadOptions.cliTitle.isNullOrBlank()) {
                Logger.logInfo("Custom Title:       ${downloadOptions.cliTitle}")
            }
            if (downloadOptions.outputPath.isNotBlank()) {
                Logger.logInfo("Output Location:    ${downloadOptions.outputPath}")
            }
            Logger.logInfo("Output Format:      ${downloadOptions.format.uppercase()}")
            Logger.logInfo("Chapters to get:    ${chaptersToDownload.size}")
            Logger.logInfo("Download Workers:   ${downloadOptions.imageWorkers}")

            val userAgentMessage = when {
                currentState.perWorkerUserAgent -> "Randomized per worker"
                else -> currentState.userAgentName
            }
            Logger.logInfo("Browser Profile:    $userAgentMessage")
            Logger.logDebug { "Full User-Agent string(s) used: ${downloadOptions.userAgents.joinToString(", ")}" }
            Logger.logInfo("---------------------------------------")


            downloadService.downloadNewSeries(downloadOptions)
            _state.update { it.copy(isBusy = false) }
        }
    }
}
