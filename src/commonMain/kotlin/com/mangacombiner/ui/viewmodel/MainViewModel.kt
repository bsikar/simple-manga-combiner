package com.mangacombiner.ui.viewmodel

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.service.*
import com.mangacombiner.ui.viewmodel.handler.*
import com.mangacombiner.ui.viewmodel.state.*
import com.mangacombiner.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.io.path.nameWithoutExtension
import kotlin.random.Random

internal class JobEditedException : CancellationException("Job was edited and needs to be restarted.")

@OptIn(FlowPreview::class)
class MainViewModel(
    internal val downloadService: DownloadService,
    internal val scraperService: ScraperService,
    internal val webDavService: WebDavService,
    internal val clipboardManager: ClipboardManager,
    internal val platformProvider: PlatformProvider,
    internal val cacheService: CacheService,
    internal val settingsRepository: SettingsRepository,
    internal val queuePersistenceService: QueuePersistenceService,
    internal val fileMover: FileMover,
    internal val backgroundDownloader: BackgroundDownloader
) : PlatformViewModel() {

    internal val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState>

    internal val _operationState = MutableStateFlow(OperationState.IDLE)
    internal val _filePickerRequest = MutableSharedFlow<FilePickerRequest>()
    val filePickerRequest = _filePickerRequest.asSharedFlow()

    internal val _logs = MutableStateFlow(listOf("Welcome to Manga Combiner!"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    internal var activeOperationJob: Job? = null
    internal var fetchChaptersJob: Job? = null
    internal var searchJob: Job? = null
    internal val queuedOperationContext = ConcurrentHashMap<String, QueuedOperation>()

    internal val activeServiceJobs = ConcurrentHashMap.newKeySet<String>()

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
                proxyUrl = savedSettings.proxyUrl,
                debugLog = savedSettings.debugLog,
                logAutoscrollEnabled = savedSettings.logAutoscrollEnabled,
                settingsLocationDescription = platformProvider.getSettingsLocationDescription(),
                isSettingsLocationOpenable = platformProvider.isSettingsLocationOpenable(),
                isCacheLocationOpenable = platformProvider.isCacheLocationOpenable(),
                cachePath = platformProvider.getTmpDir(),
                zoomFactor = savedSettings.zoomFactor,
                fontSizePreset = savedSettings.fontSizePreset,
                outputPath = initialOutputPath
            )
        )
        state = _state.asStateFlow()
        Logger.logDebug { "ViewModel initialized with loaded settings." }
        loadQueueFromCache()
        setupListeners()
    }

    private fun loadQueueFromCache() {
        val loadedOperations = queuePersistenceService.loadQueue() ?: return

        val restoredJobs = loadedOperations.mapNotNull { op ->
            val seriesSlug = op.seriesUrl.toSlug()
            val seriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")
            val latestOp = queuePersistenceService.loadOperationMetadata(seriesDir.absolutePath) ?: op

            queuedOperationContext[latestOp.jobId] = latestOp

            val selectedChapters = latestOp.chapters.filter { it.selectedSource != null }
            if (selectedChapters.isEmpty()) {
                queuedOperationContext.remove(latestOp.jobId)
                return@mapNotNull null
            }

            val downloadedCount = selectedChapters.count { it.availableSources.contains(ChapterSource.CACHE) }
            val totalCount = selectedChapters.size
            val progress = if (totalCount > 0) downloadedCount.toFloat() / totalCount else 0f

            val status = if (progress >= 1.0f) "Completed" else "Paused"

            DownloadJob(
                id = latestOp.jobId,
                title = latestOp.customTitle,
                progress = progress,
                status = status,
                totalChapters = totalCount,
                downloadedChapters = downloadedCount,
                isIndividuallyPaused = status != "Completed"
            )
        }

        _state.update { it.copy(downloadQueue = restoredJobs, isQueueGloballyPaused = false) }
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
                .debounce(500)
                .collect { settingsToSave ->
                    settingsRepository.saveSettings(settingsToSave)
                    Logger.logDebug { "Settings saved automatically due to state change." }
                }
        }
        viewModelScope.launch {
            state.map { it.downloadQueue }.collect {
                updateOverallProgress()
            }
        }
        // Listen for status updates from the background downloader service
        viewModelScope.launch {
            backgroundDownloader.jobStatusFlow.collect { update ->
                handleJobStatusUpdate(update)
            }
        }
        // Reactive Queue Processor
        viewModelScope.launch(Dispatchers.IO) {
            Logger.logDebug { "Queue processor listener started." }
            state.map { Triple(it.batchWorkers, it.downloadQueue, it.isQueueGloballyPaused) }
                .distinctUntilChanged()
                .collect { (batchWorkers, queue, isQueuePaused) ->

                    if (isQueuePaused) {
                        // If queue is globally paused, stop all active service jobs.
                        if (activeServiceJobs.isNotEmpty()) {
                            Logger.logDebug { "Queue globally paused. Stopping all active jobs." }
                            backgroundDownloader.stopAllJobs()
                            activeServiceJobs.clear()
                        }
                        return@collect
                    }

                    val packagingJobs = queue.filter { job ->
                        job.status.startsWith("Packaging") && !job.isIndividuallyPaused
                    }

                    val otherRunnableJobs = queue.filter { job ->
                        !job.status.startsWith("Packaging") &&
                                !job.isIndividuallyPaused &&
                                job.status !in listOf("Completed", "Cancelled") &&
                                !job.status.startsWith("Error")
                    }

                    // The concurrent limit only applies to non-packaging jobs
                    val desiredDownloadingJobIds = otherRunnableJobs
                        .take(batchWorkers)
                        .map { it.id }
                        .toSet()

                    // All packaging jobs are desired to continue running
                    val desiredPackagingJobIds = packagingJobs
                        .map { it.id }
                        .toSet()

                    val desiredActiveJobIds = desiredDownloadingJobIds + desiredPackagingJobIds

                    val jobsToStop = activeServiceJobs - desiredActiveJobIds
                    val jobsToStart = desiredActiveJobIds - activeServiceJobs

                    if (jobsToStop.isEmpty() && jobsToStart.isEmpty()) {
                        return@collect
                    }

                    // --- Perform side effects (stopping/starting jobs) ---
                    jobsToStop.forEach { jobId ->
                        Logger.logDebug { "Throttling job due to lower priority: $jobId" }
                        backgroundDownloader.stopJob(jobId)
                        activeServiceJobs.remove(jobId)
                    }

                    jobsToStart.forEach { jobId ->
                        Logger.logDebug { "Activating job due to high priority: $jobId" }
                        startJob(jobId)
                    }

                    // --- Atomically update the UI state ---
                    _state.update { currentState ->
                        val newQueue = currentState.downloadQueue.map { job ->
                            when (job.id) {
                                in jobsToStop -> job.copy(status = "Paused") // Throttled jobs are now Paused
                                in jobsToStart -> {
                                    if (queuedOperationContext.containsKey(job.id)) {
                                        job.copy(status = "Waiting...") // Started jobs are now Waiting
                                    } else {
                                        job.copy(status = "Error: Context not found")
                                    }
                                }
                                else -> job // No change
                            }
                        }
                        currentState.copy(downloadQueue = newQueue)
                    }
                }
        }
        // Reactive Queue Persistence
        viewModelScope.launch {
            state
                .map { uiState -> uiState.downloadQueue.mapNotNull { job -> queuedOperationContext[job.id] } }
                .distinctUntilChanged()
                .debounce(1000)
                .collect { operationsToSave ->
                    if (operationsToSave.isNotEmpty()) {
                        queuePersistenceService.saveQueue(operationsToSave)
                    } else {
                        queuePersistenceService.clearQueueCache()
                    }
                }
        }
        Logger.logDebug { "ViewModel listeners set up." }
    }

    private fun handleJobStatusUpdate(update: JobStatusUpdate) {
        _state.update { state ->
            // If job finished, remove it from our active jobs tracking set.
            if (update.isFinished) {
                activeServiceJobs.remove(update.jobId)
            }

            val updatedQueue = state.downloadQueue.map { job ->
                if (job.id != update.jobId) {
                    return@map job
                }

                // If the service reports cancellation, check if it was due to a pause action.
                if (update.status == "Cancelled") {
                    // If the UI already thinks the job is paused (due to throttling or user action), then keep it as "Paused".
                    if (state.isQueueGloballyPaused || job.isIndividuallyPaused || job.status == "Paused") {
                        return@map job.copy(status = "Paused")
                    }
                }

                val newStatus = update.status ?: job.status
                var newDownloadedChapters = job.downloadedChapters + (update.downloadedChapters ?: 0)
                var newProgress = job.progress

                if (newStatus == "Completed") {
                    newDownloadedChapters = job.totalChapters
                    newProgress = 1f
                } else if (update.downloadedChapters != null && update.downloadedChapters > 0) {
                    // This is a chapter completion update. Update both counter and progress.
                    newProgress = if (job.totalChapters > 0) newDownloadedChapters.toFloat() / job.totalChapters else 0f
                } else if (update.progress != null) {
                    // This is a sub-step progress update for an in-flight chapter. Only update progress.
                    newProgress = if (job.totalChapters > 0) (job.downloadedChapters + update.progress) / job.totalChapters else 0f
                }

                job.copy(
                    status = newStatus,
                    progress = newProgress,
                    downloadedChapters = newDownloadedChapters
                )
            }
            state.copy(downloadQueue = updatedQueue)
        }
    }

    fun onEvent(event: Event) {
        Logger.logDebug { "Received event: ${event::class.simpleName}" }
        when (event) {
            is Event.Search -> handleSearchEvent(event)
            is Event.WebDav -> handleWebDavEvent(event)
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
        Logger.logDebug { "File selected: $path" }
        startFromFile(path)
    }

    fun onFolderSelected(path: String, type: FilePickerRequest.PathType) {
        Logger.logDebug { "Folder selected for type '$type': $path" }
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
            FilePickerRequest.PathType.JOB_OUTPUT -> {
                val jobId = _state.value.editingJobId ?: return
                val oldContext = queuedOperationContext[jobId] ?: return
                val newContext = oldContext.copy(outputPath = path)
                queuedOperationContext[jobId] = newContext
                _state.update { it.copy(editingJobContext = newContext) }
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
        Logger.logDebug { "Checking for existence of output file: ${outputFile.absolutePath}" }
        if (outputFile.exists()) {
            Logger.logDebug { "Output file exists." }
            _state.update { it.copy(outputFileExists = true) }
            if (s.sourceFilePath == null) {
                analyzeLocalFile(outputFile)
            }
        } else {
            Logger.logDebug { "Output file does not exist." }
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
            Logger.logInfo("Analyzing local file: ${file.name}")
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

        fetchChaptersJob?.cancel()
        fetchChaptersJob = viewModelScope.launch {
            try {
                _state.update { it.copy(isFetchingChapters = true) }
                val s = _state.value
                val seriesSlug = url.toSlug()
                val cachedChapterStatus = cacheService.getCachedChapterStatus(seriesSlug)
                val localChapterMap = s.localChaptersForSync
                val failedChapterTitles = s.failedItemsForSync.keys.map { SlugUtils.toComparableKey(it) }.toSet()
                val preselectedNames = s.chaptersToPreselect

                val client = createHttpClient(s.proxyUrl)
                Logger.logInfo("Fetching chapter list for: $url")
                val userAgent = UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first()
                val (seriesMetadata, chapters) = scraperService.fetchSeriesDetails(client, url, userAgent)
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

                        val initialSource = when {
                            sanitizedTitle in preselectedNames -> {
                                if (isBroken) ChapterSource.WEB else ChapterSource.CACHE
                            }
                            preselectedNames.isNotEmpty() -> null
                            isRetry || isBroken -> ChapterSource.WEB
                            else -> getChapterDefaultSource(chapter)
                        }

                        chapter.copy(selectedSource = initialSource)

                    }.sortedWith(compareBy(naturalSortComparator) { it.title })

                    _state.update {
                        it.copy(
                            seriesMetadata = seriesMetadata,
                            customTitle = seriesMetadata.title,
                            fetchedChapters = allChapters,
                            showChapterDialog = true,
                            chaptersToPreselect = emptySet()
                        )
                    }
                } else {
                    Logger.logError("Could not find any chapters at the provided URL.")
                }
            } catch (e: NetworkException) {
                Logger.logError("Failed to fetch chapters due to network error", e)
                _state.update {
                    it.copy(
                        showNetworkErrorDialog = true,
                        networkErrorMessage = "Chapter fetching failed. Please check your network connection."
                    )
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Logger.logError("Failed to fetch chapters", e)
                }
            } finally {
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

            downloadService.processorService.createEpubFromFolders(
                mangaTitle,
                folders.distinct(),
                tempOutputFile,
                s.seriesUrl,
                failedChapters,
                s.seriesMetadata
            )

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
        Logger.logDebug { "Resetting UI state after operation." }
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
            isPaused = { _operationState.value == OperationState.PAUSED },
            dryRun = false,
            onProgressUpdate = { progress, status ->
                _state.update { it.copy(progress = progress, progressStatusText = status) }
            },
            onChapterCompleted = {}
        )
    }

    // --- Queue Logic ---

    internal fun getJobContext(jobId: String): QueuedOperation? = queuedOperationContext[jobId]

    internal fun addJobToQueueAndResetState(op: QueuedOperation) {
        queuedOperationContext[op.jobId] = op

        val selectedChapters = op.chapters.filter { it.selectedSource != null }
        val newJob = DownloadJob(op.jobId, op.customTitle, 0f, "Queued", selectedChapters.size, 0)
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
                completionMessage = "${op.customTitle} added to queue.",
                showAddDuplicateDialog = false,
                jobContextToAdd = null
            )
        }
        Logger.logInfo("Job '${op.customTitle}' (${op.jobId}) added to queue.")
    }

    private fun createQueuedOperationFromCurrentState(): QueuedOperation {
        val s = state.value
        val jobId = UUID.randomUUID().toString()
        val title = s.customTitle.ifBlank { s.seriesUrl.toSlug().replace('-', ' ').titlecase() }

        val userAgents = when {
            s.perWorkerUserAgent -> List(s.workers) { UserAgent.browsers.values.random(Random) }
            s.userAgentName == "Random" -> listOf(UserAgent.browsers.values.random(Random))
            else -> listOf(UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first())
        }

        return QueuedOperation(
            jobId = jobId,
            seriesUrl = s.seriesUrl,
            customTitle = title,
            outputFormat = s.outputFormat,
            outputPath = s.outputPath,
            chapters = s.fetchedChapters,
            workers = s.workers,
            userAgents = userAgents,
            seriesMetadata = s.seriesMetadata
        )
    }

    internal fun addCurrentJobToQueue() {
        val s = state.value
        val selectedChapters = s.fetchedChapters.filter { it.selectedSource != null }
        if (selectedChapters.isEmpty()) {
            Logger.logError("No chapters selected to add to the queue.")
            return
        }

        val jobContext = createQueuedOperationFromCurrentState()
        val isDuplicate = queuedOperationContext.values.any { it.seriesUrl == jobContext.seriesUrl }

        if (isDuplicate) {
            _state.update {
                it.copy(
                    showAddDuplicateDialog = true,
                    jobContextToAdd = jobContext
                )
            }
        } else {
            addJobToQueueAndResetState(jobContext)
        }
    }

    internal fun cancelJob(jobId: String) {
        backgroundDownloader.stopJob(jobId)
        activeServiceJobs.remove(jobId)
        _state.update {
            it.copy(
                downloadQueue = it.downloadQueue.filterNot { job -> job.id == jobId },
                editingJobId = if (it.editingJobId == jobId) null else it.editingJobId,
                editingJobContext = if (it.editingJobId == jobId) null else it.editingJobContext
            )
        }
        queuedOperationContext.remove(jobId)
        Logger.logInfo("Job $jobId was cancelled and removed from queue.")
    }

    internal fun clearCompletedJobs() {
        val completedJobs = _state.value.downloadQueue.filter {
            it.status == "Completed" || it.status.startsWith("Error")
        }

        if (completedJobs.isNotEmpty()) {
            completedJobs.forEach { queuedOperationContext.remove(it.id) }
            _state.update {
                it.copy(downloadQueue = it.downloadQueue - completedJobs.toSet())
            }
        }
        Logger.logInfo("Cleared completed jobs from queue.")
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
            val updatedQueue = it.downloadQueue.map { job ->
                if (job.id == event.jobId) job.copy(title = event.title) else job
            }
            it.copy(
                downloadQueue = updatedQueue,
                editingJobId = null,
                editingJobContext = null
            )
        }
        Logger.logInfo("Updated settings for job: ${event.title}")
    }

    private fun startJob(jobId: String) {
        if (activeServiceJobs.contains(jobId)) return

        val op = queuedOperationContext[jobId]
        if (op != null) {
            backgroundDownloader.startJob(op)
            activeServiceJobs.add(jobId)
        } else {
            Logger.logError("Could not find operation context for job $jobId")
            _state.update {
                it.copy(downloadQueue = it.downloadQueue.map { j ->
                    if (j.id == jobId) j.copy(status = "Error: Context not found") else j
                })
            }
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
