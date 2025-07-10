package com.mangacombiner.ui.viewmodel

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.service.CacheService
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.ui.viewmodel.handler.handleCacheEvent
import com.mangacombiner.ui.viewmodel.handler.handleDownloadEvent
import com.mangacombiner.ui.viewmodel.handler.handleLogEvent
import com.mangacombiner.ui.viewmodel.handler.handleOperationEvent
import com.mangacombiner.ui.viewmodel.handler.handleSearchEvent
import com.mangacombiner.ui.viewmodel.handler.handleSettingsEvent
import com.mangacombiner.ui.viewmodel.state.Chapter
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.ui.viewmodel.state.toAppSettings
import com.mangacombiner.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.nameWithoutExtension
import kotlin.random.Random

@OptIn(FlowPreview::class)
class MainViewModel(
    internal val downloadService: DownloadService,
    internal val scraperService: ScraperService,
    internal val clipboardManager: ClipboardManager,
    internal val platformProvider: PlatformProvider,
    internal val cacheService: CacheService,
    internal val settingsRepository: SettingsRepository,
    internal val fileMover: FileMover
) : PlatformViewModel() {

    internal val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState>

    internal val _operationState = MutableStateFlow(OperationState.IDLE)
    internal val _filePickerRequest = MutableSharedFlow<FilePickerRequest>()
    val filePickerRequest = _filePickerRequest.asSharedFlow()

    internal val _logs = MutableStateFlow(listOf("Welcome to Manga Combiner!"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    internal var activeOperationJob: Job? = null

    init {
        val savedSettings = settingsRepository.loadSettings()
        Logger.isDebugEnabled = savedSettings.debugLog
        val effectiveDefaultLocation = savedSettings.defaultOutputLocation
        val initialOutputPath = when (effectiveDefaultLocation) {
            "Downloads" -> platformProvider.getUserDownloadsDir() ?: ""
            "Documents" -> platformProvider.getUserDocumentsDir() ?: ""
            "Desktop" -> platformProvider.getUserDesktopDir() ?: ""
            "Custom" -> savedSettings.customDefaultOutputPath
            else -> ""
        }

        _state = MutableStateFlow(
            UiState(
                theme = savedSettings.theme,
                defaultOutputLocation = effectiveDefaultLocation,
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
                systemDarkTheme = savedSettings.systemDarkTheme,
                outputPath = initialOutputPath
            )
        )
        state = _state.asStateFlow()

        setupListeners()
        setPlaceholderQueue()
    }

    private fun setupListeners() {
        Logger.addListener { logMessage ->
            _logs.update { it + logMessage }
        }
        viewModelScope.launch {
            _operationState.collect {
                _state.update { uiState -> uiState.copy(operationState = it) }
            }
        }
        viewModelScope.launch {
            state
                .map { it.toAppSettings() }
                .distinctUntilChanged()
                .collect { settingsToSave ->
                    settingsRepository.saveSettings(settingsToSave)
                    Logger.logDebug { "Settings saved automatically." }
                }
        }
    }

    private fun setPlaceholderQueue() {
        _state.update {
            it.copy(
                downloadQueue = listOf(
                    DownloadJob(id = "1", title = "One-Punch Man", progress = 0.25f, status = "Downloading", totalChapters = 180, downloadedChapters = 45),
                    DownloadJob(id = "2", title = "Jujutsu Kaisen", progress = 0f, status = "Queued", totalChapters = 230, downloadedChapters = 0),
                    DownloadJob(id = "3", title = "My Hero Academia", progress = 0.5f, status = "Paused", totalChapters = 420, downloadedChapters = 210),
                    DownloadJob(id = "4", title = "Solo Leveling", progress = 1f, status = "Completed", totalChapters = 179, downloadedChapters = 179),
                )
            )
        }
    }

    fun onEvent(event: Event) {
        when (event) {
            is Event.Search -> handleSearchEvent(event)
            is Event.Download -> handleDownloadEvent(event)
            is Event.Settings -> handleSettingsEvent(event)
            is Event.Cache -> handleCacheEvent(event)
            is Event.Operation -> handleOperationEvent(event)
            is Event.Log -> handleLogEvent(event)
            is Event.Navigate -> _state.update { it.copy(currentScreen = event.screen) }
            is Event.ToggleAboutDialog -> _state.update { it.copy(showAboutDialog = event.show) }
        }
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
                _state.update { it.copy(
                    customDefaultOutputPath = path,
                    outputPath = path,
                    defaultOutputLocation = "Custom"
                ) }
            }
        }
        checkOutputFileExistence()
    }

    internal fun getChapterDefaultSource(chapter: Chapter): ChapterSource {
        return when {
            chapter.availableSources.contains(ChapterSource.LOCAL) -> ChapterSource.LOCAL
            chapter.availableSources.contains(ChapterSource.CACHE) -> ChapterSource.CACHE
            else -> ChapterSource.WEB
        }
    }

    internal fun checkOutputFileExistence() {
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
                _state.update { it.copy(sourceFilePath = null, localChaptersForSync = emptyMap(), failedItemsForSync = emptyMap()) }
            }
        }
    }

    internal fun analyzeLocalFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!file.exists() || !file.isFile) {
                Logger.logError("Path is not a valid file for analysis: ${file.path}")
                return@launch
            }
            _state.update { it.copy(isAnalyzingFile = true) }

            val (chapterSlugs, url, failedItems) = downloadService.processorService.getChaptersAndInfoFromFile(file)

            _state.update { currentState ->
                if (chapterSlugs.isEmpty() && url == null) {
                    Logger.logInfo("No chapters or URL could be identified in the file: ${file.name}.")
                    currentState.copy(isAnalyzingFile = false, sourceFilePath = null, localChaptersForSync = emptyMap(), failedItemsForSync = emptyMap())
                } else {
                    if (url != null) Logger.logInfo("Found embedded series URL in ${file.name}: $url")
                    if (failedItems.isNotEmpty()) Logger.logInfo("Found ${failedItems.size} chapters with download failures in the file.")
                    currentState.copy(
                        isAnalyzingFile = false,
                        seriesUrl = currentState.seriesUrl.ifBlank { url ?: "" },
                        sourceFilePath = file.path,
                        localChaptersForSync = chapterSlugs.associateBy { SlugUtils.toComparableKey(it) },
                        failedItemsForSync = failedItems
                    )
                }
            }
        }
    }

    internal fun fetchChapters() {
        val url = _state.value.seriesUrl
        if (url.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isFetchingChapters = true) }
            val seriesSlug = url.toSlug()
            val cachedChapterStatus = cacheService.getCachedChapterStatus(seriesSlug)
            val localChapterMap = _state.value.localChaptersForSync
            val failedChapterTitles = _state.value.failedItemsForSync.keys.map { SlugUtils.toComparableKey(it) }.toSet()

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
                    val isCached = cachedChapterStatus.containsKey(sanitizedTitle)
                    val isBroken = isCached && cachedChapterStatus[sanitizedTitle] == false
                    val isRetry = comparableKey in failedChapterTitles

                    val sources = mutableSetOf(ChapterSource.WEB)
                    if (isLocal) sources.add(ChapterSource.LOCAL)
                    if (isCached) sources.add(ChapterSource.CACHE)

                    val chapter = Chapter(
                        url = chapUrl,
                        title = title,
                        availableSources = sources,
                        selectedSource = null, // Set later
                        localSlug = originalLocalSlug,
                        isRetry = isRetry,
                        isBroken = isBroken
                    )

                    val initialSource = if (isRetry || isBroken) ChapterSource.WEB else getChapterDefaultSource(chapter)

                    chapter.copy(selectedSource = initialSource)

                }.sortedWith(compareBy(naturalSortComparator) { it.title })

                _state.update {
                    it.copy(isFetchingChapters = false, fetchedChapters = allChapters, showChapterDialog = true)
                }
            } else {
                Logger.logError("Could not find any chapters at the provided URL.")
                _state.update { it.copy(isFetchingChapters = false) }
            }
        }
    }

    internal fun startOperation(isRetry: Boolean = false) {
        activeOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _operationState.value = OperationState.RUNNING
                _state.update { it.copy(progress = 0f, progressStatusText = "Starting operation...") }
                val s = _state.value
                val tempDir = File(platformProvider.getTmpDir())
                val tempUpdateDir = File(tempDir, "manga-update-${System.currentTimeMillis()}").apply { mkdirs() }

                val chaptersForOperation = if (isRetry) {
                    val failedTitles = s.lastDownloadResult?.failedChapters?.keys ?: emptySet()
                    s.fetchedChapters.filter { it.title in failedTitles }
                } else {
                    s.fetchedChapters.filter { it.selectedSource != null }
                }

                if (chaptersForOperation.isEmpty()) {
                    Logger.logError("No chapters selected for operation. Aborting.")
                    return@launch
                }

                val allChapterFolders = if (isRetry) {
                    s.lastDownloadResult?.successfulFolders?.toMutableList() ?: mutableListOf()
                } else {
                    mutableListOf()
                }

                val seriesSlug = if (s.seriesUrl.isNotBlank()) s.seriesUrl.toSlug() else ""
                val tempSeriesDir = if (seriesSlug.isNotBlank()) File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() } else tempDir

                if (!isRetry) {
                    val chaptersToExtract = chaptersForOperation.filter { it.selectedSource == ChapterSource.LOCAL }
                    if (s.sourceFilePath != null && chaptersToExtract.isNotEmpty()) {
                        _state.update { it.copy(progress = 0.1f, progressStatusText = "Extracting local chapters...") }
                        allChapterFolders.addAll(
                            downloadService.processorService.extractChaptersToDirectory(
                                File(s.sourceFilePath),
                                chaptersToExtract.mapNotNull { it.localSlug },
                                tempUpdateDir
                            )
                        )
                    }

                    val chaptersFromCache = chaptersForOperation.filter { it.selectedSource == ChapterSource.CACHE }
                    chaptersFromCache.forEach {
                        allChapterFolders.add(File(tempSeriesDir, FileUtils.sanitizeFilename(it.title)))
                    }
                }

                val chaptersToDownload = chaptersForOperation.filter { it.selectedSource == ChapterSource.WEB }
                if (chaptersToDownload.isNotEmpty()) {
                    val downloadOptions = createDownloadOptions(s, s.seriesUrl, chaptersToDownload)
                    _state.update { it.copy(activeDownloadOptions = downloadOptions) }
                    val downloadResult = downloadService.downloadChapters(downloadOptions, tempSeriesDir)

                    if(downloadResult != null) {
                        allChapterFolders.addAll(downloadResult.successfulFolders)
                        _state.update { it.copy(lastDownloadResult = downloadResult) }

                        if (downloadResult.failedChapters.isNotEmpty()) {
                            _state.update { it.copy(showBrokenDownloadDialog = true) }
                            return@launch
                        }
                    }
                }

                ensureActive()
                packageFinalFile(allChapterFolders)

            } catch (e: CancellationException) {
                Logger.logInfo("Operation cancelled by user.")
                if (_state.value.deleteCacheOnCancel) {
                    val seriesSlug = if (_state.value.seriesUrl.isNotBlank()) _state.value.seriesUrl.toSlug() else ""
                    if (seriesSlug.isNotBlank()) {
                        val tempSeriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")
                        if (tempSeriesDir.exists()) {
                            Logger.logInfo("Deleting temporary files for cancelled job...")
                            tempSeriesDir.deleteRecursively()
                        }
                    }
                }
                throw e
            } finally {
                withContext(NonCancellable) {
                    _operationState.value = OperationState.IDLE
                    // Only reset the UI if the operation didn't complete with a result dialog showing.
                    if (!_state.value.showCompletionDialog && !_state.value.showBrokenDownloadDialog) {
                        resetUiStateAfterOperation()
                    }
                    activeOperationJob = null
                }
            }
        }
    }

    internal fun packageFinalFile(folders: List<File>, failedChapters: Map<String, List<String>>? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _state.value
            if (folders.isNotEmpty()) {
                val mangaTitle = s.customTitle.ifBlank {
                    s.seriesUrl.toSlug().replace('-', ' ').titlecase().ifBlank { "Untitled" }
                }
                _state.update { it.copy(progress = 0.95f, progressStatusText = "Packaging ${folders.size} chapters...") }

                val finalOutputPath = s.outputPath
                val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${s.outputFormat}"

                val tempOutputFile = File(platformProvider.getTmpDir(), finalFileName)

                if (s.outputFormat == "cbz") {
                    downloadService.processorService.createCbzFromFolders(mangaTitle, folders.distinct(), tempOutputFile, s.seriesUrl, failedChapters)
                } else {
                    downloadService.processorService.createEpubFromFolders(mangaTitle, folders.distinct(), tempOutputFile, s.seriesUrl, failedChapters)
                }

                ensureActive()

                if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                    _state.update { it.copy(progress = 1.0f, progressStatusText = "Moving final file...") }
                    val finalPath = fileMover.moveToFinalDestination(tempOutputFile, finalOutputPath, finalFileName)
                    val message = if (finalPath.isNotBlank()) {
                        "Download complete: $finalPath"
                    } else {
                        "Error: Failed to move file to final destination."
                    }
                    _state.update { it.copy(completionMessage = message, showCompletionDialog = true) }
                    Logger.logInfo("\n$message")
                } else {
                    val message = "Packaging failed. Output file was not created or is empty."
                    _state.update { it.copy(completionMessage = "Error: $message") }
                    Logger.logError(message)
                }
            } else {
                Logger.logInfo("No chapters to process. Operation finished.")
            }
            _operationState.value = OperationState.IDLE
            resetUiStateAfterOperation()
        }
    }

    internal fun resetUiStateAfterOperation() {
        _state.update {
            it.copy(
                activeDownloadOptions = null,
                deleteCacheOnCancel = false,
                progress = 0f,
                progressStatusText = "",
                sourceFilePath = null,
                localChaptersForSync = emptyMap(),
                failedItemsForSync = emptyMap(),
                fetchedChapters = emptyList(),
                seriesUrl = "",
                customTitle = "",
                lastDownloadResult = null,
                showCompletionDialog = false
            )
        }
        Logger.logInfo("--- Operation Complete ---")
    }

    private fun startFromFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                Logger.logError("Selected path is not a valid file: $path")
                return@launch
            }
            _state.update { it.copy(customTitle = file.toPath().nameWithoutExtension.toString()) }
            analyzeLocalFile(file)
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
            outputPath = s.outputPath,
            operationState = _operationState,
            dryRun = s.dryRun,
            onProgressUpdate = { progress, status ->
                _state.update { it.copy(progress = progress, progressStatusText = status) }
            }
        )
    }
}
