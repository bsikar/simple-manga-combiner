package com.mangacombiner.ui.viewmodel

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.model.IpInfo
import com.mangacombiner.model.ProxyType
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.service.*
import com.mangacombiner.ui.viewmodel.handler.*
import com.mangacombiner.ui.viewmodel.state.*
import com.mangacombiner.util.*
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
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
    internal val backgroundDownloader: BackgroundDownloader,
    internal val proxyMonitorService: ProxyMonitorService,
    internal val networkInterceptor: NetworkInterceptor
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

    private data class QueueProcessorState(
        val batchWorkers: Int,
        val queue: List<DownloadJob>,
        val isPaused: Boolean,
        val isBlocked: Boolean,
        val isVerifying: Boolean,
        val proxyConnectionState: ProxyMonitorService.ProxyConnectionState
    )

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
                proxyType = savedSettings.proxyType,
                proxyHost = savedSettings.proxyHost,
                proxyPort = savedSettings.proxyPort,
                proxyUser = savedSettings.proxyUser,
                proxyPass = savedSettings.proxyPass,
                debugLog = savedSettings.debugLog,
                logAutoscrollEnabled = savedSettings.logAutoscrollEnabled,
                settingsLocationDescription = platformProvider.getSettingsLocationDescription(),
                isSettingsLocationOpenable = platformProvider.isSettingsLocationOpenable(),
                isCacheLocationOpenable = platformProvider.isCacheLocationOpenable(),
                cachePath = platformProvider.getTmpDir(),
                zoomFactor = savedSettings.zoomFactor,
                fontSizePreset = savedSettings.fontSizePreset,
                outputPath = initialOutputPath,
                offlineMode = savedSettings.offlineMode,
                proxyEnabledOnStartup = savedSettings.proxyEnabledOnStartup,
                proxyConnectionState = ProxyMonitorService.ProxyConnectionState.UNKNOWN,
                ipLookupUrl = savedSettings.ipLookupUrl
            )
        )
        state = _state.asStateFlow()
        Logger.logDebug { "ViewModel initialized with loaded settings." }

        // Set up proxy monitoring
        viewModelScope.launch {
            proxyMonitorService.connectionState.collect { connectionState ->
                _state.update {
                    it.copy(
                        isNetworkBlocked = when (connectionState) {
                            ProxyMonitorService.ProxyConnectionState.DISCONNECTED,
                            ProxyMonitorService.ProxyConnectionState.UNKNOWN,
                            ProxyMonitorService.ProxyConnectionState.RECONNECTING ->
                                it.proxyEnabledOnStartup && it.proxyType != ProxyType.NONE
                            ProxyMonitorService.ProxyConnectionState.CONNECTED,
                            ProxyMonitorService.ProxyConnectionState.DISABLED -> false
                        },
                        proxyConnectionState = connectionState,
                        killSwitchActive = connectionState == ProxyMonitorService.ProxyConnectionState.DISCONNECTED
                    )
                }

                // Log state changes
                when (connectionState) {
                    ProxyMonitorService.ProxyConnectionState.DISCONNECTED -> {
                        Logger.logError("🔴 KILL SWITCH ACTIVE - All network operations blocked")
                    }
                    ProxyMonitorService.ProxyConnectionState.CONNECTED -> {
                        Logger.logInfo("🟢 Proxy connected - Network operations allowed")
                        _state.update { it.copy(isInitialProxyCheckRunning = false) }
                    }
                    ProxyMonitorService.ProxyConnectionState.RECONNECTING -> {
                        Logger.logInfo("🟡 Attempting to reconnect to proxy...")
                    }
                    else -> {}
                }
            }
        }

        // Trigger proxy verification on startup if enabled
        if (savedSettings.proxyEnabledOnStartup && savedSettings.proxyUrl.isNotBlank()) {
            _state.update { it.copy(isInitialProxyCheckRunning = true) }
            verifyProxyConnection()
        }

        loadQueueFromCache()
        setupListeners()
    }

    private fun startProxyMonitoring() {
        val s = state.value
        val proxyUrl = buildProxyUrl(
            s.proxyType,
            s.proxyHost,
            s.proxyPort,
            s.proxyUser,
            s.proxyPass
        )
        val lookupUrl = s.ipLookupUrl

        if (proxyUrl != null) {
            viewModelScope.launch {
                try {
                    networkInterceptor.checkNetworkAllowed()
                    val client = createHttpClient(proxyUrl)
                    val response = client.get(lookupUrl)
                    if (response.status.isSuccess()) {
                        val ipInfo = response.body<IpInfo>()
                        val proxyIp = ipInfo.ip

                        // Start monitoring with the expected IP
                        proxyMonitorService.startMonitoring(
                            ProxyMonitorService.ProxyConfig(proxyUrl, proxyIp, lookupUrl),
                            viewModelScope
                        )
                    } else {
                        // Start monitoring without expected IP if ipinfo fails
                        proxyMonitorService.startMonitoring(
                            ProxyMonitorService.ProxyConfig(proxyUrl, lookupUrl = lookupUrl),
                            viewModelScope
                        )
                    }
                    client.close()
                } catch (e: ProxyKillSwitchException) {
                    Logger.logError("Kill switch prevented initial proxy check: ${e.message}")
                    proxyMonitorService.startMonitoring(
                        ProxyMonitorService.ProxyConfig(proxyUrl, lookupUrl = lookupUrl),
                        viewModelScope
                    )
                } catch (e: Exception) {
                    Logger.logError("Failed to get proxy IP for monitoring", e)
                    proxyMonitorService.startMonitoring(
                        ProxyMonitorService.ProxyConfig(proxyUrl, lookupUrl = lookupUrl),
                        viewModelScope
                    )
                }
            }
        } else {
            _state.update { it.copy(isInitialProxyCheckRunning = false) }
        }
    }

    private fun restartProxyMonitoringIfNeeded() {
        if (state.value.proxyEnabledOnStartup) {
            proxyMonitorService.stopMonitoring()
            startProxyMonitoring()
        }
    }

    internal fun verifyProxyConnection() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    proxyStatus = ProxyStatus.VERIFYING,
                    proxyVerificationMessage = "Running comprehensive proxy test...",
                    ipInfoResult = null,
                    ipCheckError = null,
                    isNetworkBlocked = true // Block network while verifying
                )
            }

            try {
                val s = state.value
                val url = buildProxyUrl(s.proxyType, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass)
                val lookupUrl = s.ipLookupUrl

                if (url == null) {
                    _state.update {
                        it.copy(
                            proxyStatus = ProxyStatus.UNVERIFIED,
                            proxyVerificationMessage = "No proxy configured.",
                            isNetworkBlocked = false // Unblock if no proxy is set
                        )
                    }
                    return@launch
                }

                Logger.logInfo("Starting comprehensive proxy verification for: $url")
                val testResult = ProxyTestUtility.runComprehensiveProxyTest(url, lookupUrl)

                if (testResult.success) {
                    val message = buildString {
                        append("✓ Proxy working correctly")
                        if (testResult.ipChanged) {
                            append("\n✓ IP changed: ${testResult.directIp} → ${testResult.proxyIp}")
                        }
                        if (testResult.killSwitchWorking) {
                            append("\n✓ Kill switch active")
                        }
                        testResult.proxyLocation?.let {
                            append("\n📍 Location: $it")
                        }
                    }

                    _state.update {
                        it.copy(
                            proxyStatus = ProxyStatus.CONNECTED,
                            proxyVerificationMessage = message,
                            ipInfoResult = IpInfo(
                                ip = testResult.proxyIp,
                                city = testResult.proxyLocation?.split(", ")?.getOrNull(0),
                                country = testResult.proxyLocation?.split(", ")?.getOrNull(1)
                            ),
                            isNetworkBlocked = false // Unblock on success
                        )
                    }

                    // Restart monitoring with verified config
                    restartProxyMonitoringIfNeeded()
                } else {
                    val message = buildString {
                        append("✗ Proxy test failed")
                        testResult.error?.let { append(": $it") }
                        if (!testResult.ipChanged && testResult.directIp != null && testResult.proxyIp != null) {
                            append("\n⚠️ IP unchanged - proxy may not be working")
                        }
                        if (!testResult.killSwitchWorking) {
                            append("\n⚠️ Kill switch not working - traffic may leak!")
                        }
                    }

                    _state.update {
                        it.copy(
                            proxyStatus = ProxyStatus.FAILED,
                            proxyVerificationMessage = message,
                            isNetworkBlocked = true // Keep blocked on failure
                        )
                    }
                }
            } catch (e: Exception) {
                val message = "Test failed: ${e.message?.take(100) ?: "Unknown error"}"
                _state.update {
                    it.copy(
                        proxyStatus = ProxyStatus.FAILED,
                        proxyVerificationMessage = message,
                        isNetworkBlocked = true // Keep blocked on failure
                    )
                }
                Logger.logError("Comprehensive proxy verification failed", e)
            } finally {
                _state.update { it.copy(isInitialProxyCheckRunning = false) }
            }
        }
    }

    internal fun fetchChapters() {
        val url = _state.value.seriesUrl
        if (url.isBlank()) return

        fetchChaptersJob?.cancel()
        fetchChaptersJob = viewModelScope.launch {
            try {
                networkInterceptor.checkNetworkAllowed() // Check kill switch

                _state.update { it.copy(isFetchingChapters = true) }
                val s = _state.value
                val seriesSlug = url.toSlug()
                val cachedChapterStatus = cacheService.getCachedChapterStatus(seriesSlug)
                val localChapterMap = s.localChaptersForSync
                val failedChapterTitles = s.failedItemsForSync.keys.map { SlugUtils.toComparableKey(it) }.toSet()
                val preselectedNames = s.chaptersToPreselect

                val client = createHttpClient(buildProxyUrl(s.proxyType, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass))
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
                            selectedSource = null,
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
            } catch (e: ProxyKillSwitchException) {
                Logger.logError("Kill switch blocked chapter fetching: ${e.message}")
                _state.update {
                    it.copy(
                        showNetworkErrorDialog = true,
                        networkErrorMessage = "Network operations are blocked. Proxy connection required."
                    )
                }
            } catch (e: NetworkException) {
                Logger.logError("Failed to fetch chapters due to network error", e)
                _state.update {
                    it.copy(
                        showNetworkErrorDialog = true,
                        networkErrorMessage = "Chapter fetching failed. Please check your network connection and proxy settings."
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
        viewModelScope.launch {
            backgroundDownloader.jobStatusFlow.collect { update ->
                handleJobStatusUpdate(update)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            Logger.logDebug { "Queue processor listener started." }
            state.map {
                QueueProcessorState(
                    it.batchWorkers,
                    it.downloadQueue,
                    it.isQueueGloballyPaused,
                    it.isNetworkBlocked,
                    it.isInitialProxyCheckRunning,
                    it.proxyConnectionState
                )
            }
                .distinctUntilChanged()
                .collect { (batchWorkers, queue, isPaused, isBlocked, isVerifying, proxyState) ->

                    val shouldStopAll = isPaused || isBlocked || isVerifying ||
                            (state.value.proxyEnabledOnStartup && proxyState == ProxyMonitorService.ProxyConnectionState.DISCONNECTED)

                    if (shouldStopAll) {
                        if (activeServiceJobs.isNotEmpty()) {
                            Logger.logDebug { "Stopping all active jobs due to: paused=$isPaused, blocked=$isBlocked, verifying=$isVerifying, killSwitch=${proxyState == ProxyMonitorService.ProxyConnectionState.DISCONNECTED}" }
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

                    val desiredDownloadingJobIds = otherRunnableJobs
                        .take(batchWorkers)
                        .map { it.id }
                        .toSet()

                    val desiredPackagingJobIds = packagingJobs
                        .map { it.id }
                        .toSet()

                    val desiredActiveJobIds = desiredDownloadingJobIds + desiredPackagingJobIds

                    val jobsToStop = activeServiceJobs - desiredActiveJobIds
                    val jobsToStart = desiredActiveJobIds - activeServiceJobs

                    if (jobsToStop.isEmpty() && jobsToStart.isEmpty()) {
                        return@collect
                    }

                    jobsToStop.forEach { jobId ->
                        Logger.logDebug { "Throttling job due to lower priority: $jobId" }
                        backgroundDownloader.stopJob(jobId)
                        activeServiceJobs.remove(jobId)
                    }

                    jobsToStart.forEach { jobId ->
                        Logger.logDebug { "Activating job due to high priority: $jobId" }
                        startJob(jobId)
                    }

                    _state.update { currentState ->
                        val newQueue = currentState.downloadQueue.map { job ->
                            when (job.id) {
                                in jobsToStop -> job.copy(status = "Paused")
                                in jobsToStart -> {
                                    if (queuedOperationContext.containsKey(job.id)) {
                                        job.copy(status = "Waiting...")
                                    } else {
                                        job.copy(status = "Error: Context not found")
                                    }
                                }
                                else -> job
                            }
                        }
                        currentState.copy(downloadQueue = newQueue)
                    }
                }
        }
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
            if (update.isFinished) {
                activeServiceJobs.remove(update.jobId)
            }

            val updatedQueue = state.downloadQueue.map { job ->
                if (job.id != update.jobId) {
                    return@map job
                }

                if (update.status == "Cancelled") {
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
                    newProgress = if (job.totalChapters > 0) newDownloadedChapters.toFloat() / job.totalChapters else 0f
                } else if (update.progress != null) {
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
                                File(s.sourceFilePath!!),
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
            _state.update { it.copy(customTitle = file.toPath().nameWithoutExtension) }
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

    internal fun buildProxyUrl(type: ProxyType, host: String, port: String, user: String, pass: String): String? {
        if (type == ProxyType.NONE || host.isBlank() || port.isBlank()) {
            return null
        }
        val scheme = when (type) {
            ProxyType.HTTP -> "http"
            ProxyType.SOCKS5 -> "socks5"
            else -> return null
        }
        val auth = if (user.isNotBlank()) {
            if (pass.isNotBlank()) {
                "${user.trim()}:${pass.trim()}@"
            } else {
                "${user.trim()}@"
            }
        } else ""
        return "$scheme://$auth${host.trim()}:${port.trim()}"
    }

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
