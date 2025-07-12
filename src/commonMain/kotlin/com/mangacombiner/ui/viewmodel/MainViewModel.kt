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
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.io.path.nameWithoutExtension
import kotlin.random.Random

private sealed class JobUpdateEvent {
    data class StatusChanged(val jobId: String, val status: String, val progress: Float?) : JobUpdateEvent()
    data class DownloadedChaptersChanged(val jobId: String, val newCount: Int) : JobUpdateEvent()
}

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
    private val jobUpdateEvents = MutableSharedFlow<JobUpdateEvent>(extraBufferCapacity = 128)

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
        setupListeners()
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
        viewModelScope.launch {
            jobUpdateEvents.collect { event ->
                handleJobUpdateEvent(event)
            }
        }
        // Reactive Queue Processor
        viewModelScope.launch(Dispatchers.IO) {
            Logger.logDebug { "Queue processor listener started." }
            state.map { Triple(it.batchWorkers, it.downloadQueue, it.isQueueGloballyPaused) }
                .distinctUntilChanged()
                .collect { (batchWorkers, queue, isQueuePaused) ->
                    val runningJobIds = runningJobCoroutines.keys.toSet()

                    if (isQueuePaused) {
                        // If the whole queue is globally paused, cancel all running jobs and mark them as paused.
                        runningJobIds.forEach { jobId ->
                            runningJobCoroutines[jobId]?.cancel()
                            runningJobCoroutines.remove(jobId)
                            jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(jobId, "Paused", null))
                        }
                        return@collect
                    }

                    // 1. Determine the ideal set of jobs that should be active ("Top N" rule).
                    // A job is startable if it's queued or was throttled by the system. User-paused jobs are ignored.
                    val desiredActiveJobIds = queue
                        .filterNot { it.isIndividuallyPaused || it.status in listOf("Completed", "Cancelled") || it.status.startsWith("Error") }
                        .take(batchWorkers)
                        .map { it.id }
                        .toSet()

                    // 2. Stop (cancel) any running job that is NOT in the desired set anymore.
                    val jobsToStop = runningJobIds - desiredActiveJobIds
                    jobsToStop.forEach { jobId ->
                        val job = queue.find { it.id == jobId }
                        if (job != null && !job.isIndividuallyPaused) { // Don't interfere with user-paused jobs
                            Logger.logDebug { "Throttling job due to lower priority: ${job.title}" }
                            runningJobCoroutines[jobId]?.cancel() // Cancel the coroutine
                            runningJobCoroutines.remove(jobId)    // Remove from running map
                            // Mark as throttled so it can be picked up again later
                            jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(jobId, "Throttled", null))
                        }
                    }

                    // 3. Activate any desired job that is not currently running.
                    val jobsToStart = desiredActiveJobIds - runningJobIds
                    jobsToStart.forEach { jobId ->
                        val job = queue.find { it.id == jobId }
                        // A job can be started if it's in a startable state and not paused by the user.
                        if (job != null && !job.isIndividuallyPaused && (job.status == "Queued" || job.status == "Throttled")) {
                            Logger.logDebug { "Activating job due to high priority: ${job.title}" }
                            startJob(job)
                        }
                    }
                }
        }
        Logger.logDebug { "ViewModel listeners set up." }
    }

    private fun handleJobUpdateEvent(event: JobUpdateEvent) {
        _state.update { state ->
            val updatedQueue = state.downloadQueue.map { job ->
                when (event) {
                    is JobUpdateEvent.StatusChanged -> if (job.id == event.jobId) {
                        job.copy(status = event.status, progress = event.progress ?: job.progress)
                    } else job
                    is JobUpdateEvent.DownloadedChaptersChanged -> if (job.id == event.jobId) {
                        job.copy(downloadedChapters = event.newCount)
                    } else job
                }
            }
            state.copy(downloadQueue = updatedQueue)
        }
    }

    fun onEvent(event: Event) {
        Logger.logDebug { "Received event: ${event::class.simpleName}" }
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

        viewModelScope.launch {
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
                        isFetchingChapters = false,
                        fetchedChapters = allChapters,
                        showChapterDialog = true,
                        chaptersToPreselect = emptySet()
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
        Logger.logInfo("Job '$title' ($jobId) added to queue.")
    }

    internal fun cancelJob(jobId: String) {
        runningJobCoroutines[jobId]?.cancel()
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
        _state.update {
            val newQueue = it.downloadQueue.filter { job ->
                job.status != "Completed" && !job.status.startsWith("Error")
            }
            it.copy(downloadQueue = newQueue)
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

    private fun startJob(job: DownloadJob) {
        if (runningJobCoroutines.containsKey(job.id)) return

        jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(job.id, "Starting", 0f))
        val jobCoroutine = viewModelScope.launch(Dispatchers.IO) {
            try {
                val op = queuedOperationContext[job.id]
                if (op != null) {
                    runQueuedOperation(op)
                } else {
                    jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(job.id, "Error: Context not found", null))
                    Logger.logError("Could not find operation context for job ${job.id}")
                }
            } finally {
                runningJobCoroutines.remove(job.id)
                Logger.logDebug { "Coroutine for job ${job.id} finished. Running jobs: ${runningJobCoroutines.size}" }
            }
        }
        runningJobCoroutines[job.id] = jobCoroutine
    }

    private suspend fun runQueuedOperation(op: QueuedOperation) {
        val webChaptersCompleted = AtomicInteger(0)
        try {
            val chaptersFromCache = op.chapters.filter { it.selectedSource == ChapterSource.CACHE }
            jobUpdateEvents.tryEmit(JobUpdateEvent.DownloadedChaptersChanged(op.jobId, chaptersFromCache.size))

            Logger.logInfo("--- Starting Queued Job: ${op.customTitle} (${op.jobId}) ---")
            jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(op.jobId, "Starting...", 0.05f))
            val tempDir = File(platformProvider.getTmpDir())
            val seriesSlug = op.seriesUrl.toSlug()
            val tempSeriesDir = File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }

            if (op.seriesUrl.isNotBlank() && tempSeriesDir.name.startsWith("manga-dl-")) {
                File(tempSeriesDir, "url.txt").writeText(op.seriesUrl)
            }

            val chaptersToDownload = op.chapters.filter { it.selectedSource == ChapterSource.WEB }
            val allChapterFolders = chaptersFromCache
                .map { File(tempSeriesDir, FileUtils.sanitizeFilename(it.title)) }
                .toMutableList()

            Logger.logDebug { "Job ${op.jobId}: ${chaptersFromCache.size} chapters from cache, ${chaptersToDownload.size} chapters to download." }
            var downloadResult: com.mangacombiner.service.DownloadResult? = null

            if (chaptersToDownload.isNotEmpty()) {
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
                    isPaused = {
                        val currentJob = state.value.downloadQueue.find { it.id == op.jobId }
                        state.value.isQueueGloballyPaused || currentJob?.isIndividuallyPaused == true
                    },
                    dryRun = false,
                    onProgressUpdate = { chapterProgress, status ->
                        val job = state.value.downloadQueue.find { it.id == op.jobId }
                        if (job != null) {
                            val overallProgress = (chaptersFromCache.size + webChaptersCompleted.get() + chapterProgress) / job.totalChapters
                            jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(op.jobId, if (job.isIndividuallyPaused) "Paused" else status, overallProgress))
                        }
                    },
                    onChapterCompleted = {
                        val job = state.value.downloadQueue.find { it.id == op.jobId }
                        if (job != null) {
                            val newCount = job.downloadedChapters + 1
                            jobUpdateEvents.tryEmit(JobUpdateEvent.DownloadedChaptersChanged(op.jobId, newCount))
                            Logger.logDebug { "Job ${op.jobId}: Chapter completed. Progress: $newCount/${job.totalChapters}" }
                        }
                        webChaptersCompleted.incrementAndGet()
                    }
                )
                downloadResult = downloadService.downloadChapters(downloadOptions, tempSeriesDir)
                downloadResult?.successfulFolders?.let { allChapterFolders.addAll(it) }
            }

            jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(op.jobId, "Packaging...", 0.99f))
            val finalFileName = "${FileUtils.sanitizeFilename(op.customTitle)}.${op.outputFormat}"
            val tempOutputFile = File(tempDir, finalFileName)

            if (op.outputFormat == "cbz") {
                downloadService.processorService.createCbzFromFolders(
                    op.customTitle, allChapterFolders, tempOutputFile, op.seriesUrl, downloadResult?.failedChapters
                )
            } else {
                downloadService.processorService.createEpubFromFolders(
                    op.customTitle, allChapterFolders, tempOutputFile, op.seriesUrl, downloadResult?.failedChapters
                )
            }

            fileMover.moveToFinalDestination(tempOutputFile, op.outputPath, finalFileName)
            jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(op.jobId, "Completed", 1f))
            Logger.logInfo("--- Finished Queued Job: ${op.customTitle} ---")
        } catch (e: Exception) {
            if (e is CancellationException) {
                jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(op.jobId, "Cancelled", null))
                Logger.logInfo("Job ${op.jobId} was cancelled by the user.")
                return
            }
            Logger.logError("Job ${op.jobId} failed", e)
            jobUpdateEvents.tryEmit(JobUpdateEvent.StatusChanged(op.jobId, "Error: ${e.message?.take(30) ?: "Unknown"}", null))
        } finally {
            queuedOperationContext.remove(op.jobId)
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
