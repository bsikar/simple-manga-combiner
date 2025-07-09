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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
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

        setupListeners()
        setInitialPaths(savedSettings.defaultOutputLocation, savedSettings.customDefaultOutputPath)
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
                .debounce(500L)
                .map { it.toAppSettings() }
                .distinctUntilChanged()
                .collect { settingsToSave ->
                    settingsRepository.saveSettings(settingsToSave)
                    Logger.logDebug { "Settings saved automatically." }
                }
        }
    }

    private fun setInitialPaths(defaultLocation: String, customPath: String) {
        val defaultDownloadsPath = platformProvider.getUserDownloadsDir() ?: ""
        val initialOutputPath = when (defaultLocation) {
            "Downloads" -> defaultDownloadsPath
            "Documents" -> platformProvider.getUserDocumentsDir() ?: ""
            "Desktop" -> platformProvider.getUserDesktopDir() ?: ""
            "Custom" -> customPath
            else -> ""
        }
        _state.update {
            it.copy(
                outputPath = initialOutputPath,
                customDefaultOutputPath = if (defaultLocation == "Custom") customPath else defaultDownloadsPath
            )
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
                _state.update { it.copy(customDefaultOutputPath = path, outputPath = path) }
            }
        }
        checkOutputFileExistence()
    }

    //region Internal Helper Functions
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
                _state.update { it.copy(sourceFilePath = null, localChaptersForSync = emptyMap()) }
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

            val (chapterSlugs, url) = downloadService.processorService.getChaptersAndInfoFromFile(file)

            _state.update { currentState ->
                if (chapterSlugs.isEmpty() && url == null) {
                    Logger.logInfo("No chapters or URL could be identified in the file: ${file.name}.")
                    currentState.copy(isAnalyzingFile = false, sourceFilePath = null, localChaptersForSync = emptyMap())
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

    internal fun fetchChapters() {
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

                    Chapter(
                        url = chapUrl,
                        title = title,
                        availableSources = sources,
                        selectedSource = getChapterDefaultSource(Chapter(chapUrl, title, sources, null, originalLocalSlug)),
                        localSlug = originalLocalSlug
                    )
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

    internal fun startOperation() {
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
                // logOperationSettings(...)

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
            analyzeLocalFile(file)
        }
    }

    internal fun createDownloadOptions(s: UiState, seriesUrl: String, chaptersToDownload: List<Chapter>): DownloadOptions {
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
    //endregion
}
