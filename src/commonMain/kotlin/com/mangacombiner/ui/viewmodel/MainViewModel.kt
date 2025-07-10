package com.mangacombiner.ui.viewmodel

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.service.CacheService
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.ui.viewmodel.handler.*
import com.mangacombiner.ui.viewmodel.state.Chapter
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.ui.viewmodel.state.toAppSettings
import com.mangacombiner.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
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
    private val queuedOperationContext = ConcurrentHashMap<String, QueuedOperation>()
    private val runningJobCoroutines = ConcurrentHashMap<String, Job>()

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
                batchWorkers = savedSettings.batchWorkers,
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
        processQueue()
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
        viewModelScope.launch {
            state.map { it.downloadQueue }.collect {
                updateOverallProgress()
            }
        }
    }

    fun onEvent(event: Event) {
        when (event) {
            is Event.Search -> handleSearchEvent(event)
            is Event.Download -> handleDownloadEvent(event)
            is Event.Settings -> handleSettingsEvent(event)
            is Event.Cache -> handleCacheEvent(event)
            is Event.Operation -> handleOperationEvent(event)
            is Event.Queue -> handleQueueEvent(event)
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
            val s = _state.value
            val seriesSlug = url.toSlug()
            val cachedChapterStatus = cacheService.getCachedChapterStatus(seriesSlug)
            val localChapterMap = s.localChaptersForSync
            val failedChapterTitles = s.failedItemsForSync.keys.map { SlugUtils.toComparableKey(it) }.toSet()
            val preselectedNames = s.chaptersToPreselect

            val userAgent = UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first()
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

                    // Determine the source based on pre-selection, then fall back to defaults
                    val initialSource = when {
                        sanitizedTitle in preselectedNames -> {
                            if (isBroken) ChapterSource.WEB else ChapterSource.CACHE
                        }
                        preselectedNames.isNotEmpty() -> null // If pre-selecting, others are deselected by default
                        isRetry || isBroken -> ChapterSource.WEB
                        else -> getChapterDefaultSource(chapter)
                    }

                    chapter.copy(selectedSource = initialSource)

                }.sortedWith(compareBy(naturalSortComparator) { it.title })

                _state.update {
                    it.copy(
                        isFetchingChapters = false,
                        fetchedChapters = allChapters,
                        showChapterDialog = true,
                        chaptersToPreselect = emptySet() // Clear after use
                    )
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

                // Save the URL to the cache directory so it can be used later
                if (s.seriesUrl.isNotBlank() && tempSeriesDir.name.startsWith("manga-dl-")) {
                    File(tempSeriesDir, "url.txt").writeText(s.seriesUrl)
                    Logger.logDebug { "Wrote series URL to ${tempSeriesDir.name}/url.txt" }
                }

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

                coroutineContext.ensureActive()
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
                    if (!_state.value.showCompletionDialog && !_state.value.showBrokenDownloadDialog) {
                        resetUiStateAfterOperation()
                    }
                    activeOperationJob = null
                }
            }
        }
    }

    internal suspend fun packageFinalFile(folders: List<File>, failedChapters: Map<String, List<String>>? = null) {
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

            coroutineContext.ensureActive()

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
                showCompletionDialog = false,
                chaptersToPreselect = emptySet()
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

    // --- Queue Logic ---

    internal fun getJobContext(jobId: String): QueuedOperation? = queuedOperationContext[jobId]

    internal fun addCurrentJobToQueue() {
        val s = state.value
        val selectedChapters = s.fetchedChapters.filter { it.selectedSource != null }
        if (selectedChapters.isEmpty()) {
            Logger.logError("No chapters selected to add to the queue.")
            return
        }

        val jobId = UUID.randomUUID().toString()
        val title = s.customTitle.ifBlank { s.seriesUrl.toSlug().replace('-', ' ').titlecase() }

        val userAgents = when {
            s.perWorkerUserAgent -> List(s.workers) { UserAgent.browsers.values.random(Random) }
            s.userAgentName == "Random" -> listOf(UserAgent.browsers.values.random(Random))
            else -> listOf(UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first())
        }

        queuedOperationContext[jobId] = QueuedOperation(
            jobId = jobId,
            seriesUrl = s.seriesUrl,
            customTitle = title,
            outputFormat = s.outputFormat,
            outputPath = s.outputPath,
            chapters = selectedChapters,
            workers = s.workers,
            userAgents = userAgents,
            dryRun = s.dryRun
        )

        val newJob = DownloadJob(jobId, title, 0f, "Queued", selectedChapters.size, 0)
        _state.update {
            it.copy(
                downloadQueue = it.downloadQueue + newJob,
                seriesUrl = "",
                customTitle = "",
                fetchedChapters = emptyList(),
                sourceFilePath = null,
                localChaptersForSync = emptyMap(),
                failedItemsForSync = emptyMap(),
                outputFileExists = false,
                completionMessage = "$title added to queue."
            )
        }
    }

    internal fun cancelJob(jobId: String) {
        runningJobCoroutines[jobId]?.cancel()
        _state.update {
            it.copy(
                downloadQueue = it.downloadQueue.filterNot { job -> job.id == jobId }
            )
        }
        queuedOperationContext.remove(jobId)
        Logger.logInfo("Job $jobId was cancelled.")
    }


    internal fun clearCompletedJobs() {
        _state.update {
            val newQueue = it.downloadQueue.filter { job ->
                job.status != "Completed" && !job.status.startsWith("Error")
            }
            it.copy(downloadQueue = newQueue)
        }
    }

    internal fun updateJob(event: Event.Queue.UpdateJob) {
        val oldContext = queuedOperationContext[event.jobId] ?: return
        queuedOperationContext[event.jobId] = oldContext.copy(
            customTitle = event.title,
            outputPath = event.outputPath,
            outputFormat = event.format,
            workers = event.workers
        )
        _state.update {
            it.copy(
                downloadQueue = it.downloadQueue.map { job ->
                    if (job.id == event.jobId) job.copy(title = event.title) else job
                }
            )
        }
    }

    private fun processQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(state.value.batchWorkers)

            state.collect { currentState ->
                if (!currentState.isQueuePaused) {
                    val nextJob = currentState.downloadQueue.firstOrNull { it.status == "Queued" }
                    if (nextJob != null) {
                        if (semaphore.tryAcquire()) {
                            updateJobState(nextJob.id, "Starting...")
                            val jobCoroutine = launch {
                                try {
                                    val op = queuedOperationContext[nextJob.id]
                                    if (op != null) {
                                        runQueuedOperation(op)
                                    } else {
                                        updateJobState(nextJob.id, "Error: Context not found")
                                    }
                                } finally {
                                    semaphore.release()
                                    runningJobCoroutines.remove(nextJob.id)
                                }
                            }
                            runningJobCoroutines[nextJob.id] = jobCoroutine
                        }
                    }
                }
            }
        }
    }

    private suspend fun runQueuedOperation(op: QueuedOperation) {
        try {
            updateJobState(op.jobId, "Downloading", 0.05f)
            val tempDir = File(platformProvider.getTmpDir())
            val seriesSlug = op.seriesUrl.toSlug()
            val tempSeriesDir = File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }

            if (op.seriesUrl.isNotBlank() && tempSeriesDir.name.startsWith("manga-dl-")) {
                File(tempSeriesDir, "url.txt").writeText(op.seriesUrl)
            }

            val chaptersToDownload = op.chapters.filter { it.selectedSource == ChapterSource.WEB }
            val allChapterFolders = op.chapters
                .filter { it.selectedSource == ChapterSource.CACHE }
                .map { File(tempSeriesDir, FileUtils.sanitizeFilename(it.title)) }
                .toMutableList()

            var downloadResult: com.mangacombiner.service.DownloadResult? = null

            if (chaptersToDownload.isNotEmpty()) {
                val operationState = MutableStateFlow(if (state.value.isQueuePaused) OperationState.PAUSED else OperationState.RUNNING)
                val downloadOptions = DownloadOptions(
                    seriesUrl = op.seriesUrl,
                    chaptersToDownload = chaptersToDownload.associate { it.url to it.title },
                    cliTitle = op.customTitle,
                    getWorkers = { op.workers },
                    exclude = emptyList(),
                    format = op.outputFormat,
                    tempDir = tempDir,
                    getUserAgents = { op.userAgents },
                    outputPath = op.outputPath,
                    operationState = operationState,
                    dryRun = op.dryRun,
                    onProgressUpdate = { progress, status ->
                        val job = state.value.downloadQueue.find { it.id == op.jobId }
                        if (job != null) {
                            val completedWebChapters = job.downloadedChapters - (job.totalChapters - chaptersToDownload.size)
                            val overallProgress = ((completedWebChapters + progress) / job.totalChapters).toFloat()
                            updateJobState(op.jobId, status, overallProgress)
                        }
                    }
                )
                downloadResult = downloadService.downloadChapters(downloadOptions, tempSeriesDir)
                downloadResult?.successfulFolders?.let { allChapterFolders.addAll(it) }
            }

            updateJobState(op.jobId, "Packaging...", 0.99f)
            val finalFileName = "${FileUtils.sanitizeFilename(op.customTitle)}.${op.outputFormat}"
            val finalOutputFile = File(op.outputPath, finalFileName)

            if (op.outputFormat == "cbz") {
                downloadService.processorService.createCbzFromFolders(
                    op.customTitle, allChapterFolders, finalOutputFile, op.seriesUrl, downloadResult?.failedChapters
                )
            } else {
                downloadService.processorService.createEpubFromFolders(
                    op.customTitle, allChapterFolders, finalOutputFile, op.seriesUrl, downloadResult?.failedChapters
                )
            }
            updateJobState(op.jobId, "Completed", 1f)
        } catch (e: Exception) {
            if (e is CancellationException) {
                updateJobState(op.jobId, "Cancelled")
                return
            }
            Logger.logError("Job ${op.jobId} failed", e)
            updateJobState(op.jobId, "Error: ${e.message?.take(30) ?: "Unknown"}")
        } finally {
            queuedOperationContext.remove(op.jobId)
        }
    }

    private fun updateJobState(jobId: String, status: String, progress: Float? = null) {
        _state.update { state ->
            val updatedQueue = state.downloadQueue.map { job ->
                if (job.id == jobId) {
                    job.copy(status = status, progress = progress ?: job.progress)
                } else {
                    job
                }
            }
            state.copy(downloadQueue = updatedQueue)
        }
    }

    private fun updateOverallProgress() {
        _state.update {
            val queue = it.downloadQueue
            if (queue.isEmpty()) {
                it.copy(overallQueueProgress = 0f)
            } else {
                val totalProgress = queue.sumOf { job -> job.progress.toDouble() }.toFloat()
                it.copy(overallQueueProgress = totalProgress / queue.size)
            }
        }
    }
}
