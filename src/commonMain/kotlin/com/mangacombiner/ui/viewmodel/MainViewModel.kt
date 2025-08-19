package com.mangacombiner.ui.viewmodel

import com.mangacombiner.data.NsfwRepository
import com.mangacombiner.data.SafeOverrideRepository
import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.model.IpInfo
import com.mangacombiner.model.ProxyType
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.service.BackgroundDownloader
import com.mangacombiner.service.CacheService
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadResult
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.EpubReaderService
import com.mangacombiner.service.JobStatusHolder
import com.mangacombiner.service.JobStatusUpdate
import com.mangacombiner.service.NetworkException
import com.mangacombiner.service.NetworkInterceptor
import com.mangacombiner.service.ProxyKillSwitchException
import com.mangacombiner.service.ProxyMonitorService
import com.mangacombiner.service.QueuePersistenceService
import com.mangacombiner.service.ReadingProgressRepository
import com.mangacombiner.service.ScraperService
import com.mangacombiner.service.UpdateService
import com.mangacombiner.service.WebDavService
import com.mangacombiner.ui.viewmodel.handler.handleCacheEvent
import com.mangacombiner.ui.viewmodel.handler.handleDownloadEvent
import com.mangacombiner.ui.viewmodel.handler.handleLogEvent
import com.mangacombiner.ui.viewmodel.handler.handleOperationEvent
import com.mangacombiner.ui.viewmodel.handler.handleQueueEvent
import com.mangacombiner.ui.viewmodel.handler.handleSearchEvent
import com.mangacombiner.ui.viewmodel.handler.handleSettingsEvent
import com.mangacombiner.ui.viewmodel.handler.handleWebDavEvent
import com.mangacombiner.ui.viewmodel.state.Chapter
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.state.LibrarySortOption
import com.mangacombiner.ui.viewmodel.state.OperationState
import com.mangacombiner.ui.viewmodel.state.ProxyStatus
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.ui.viewmodel.state.toAppSettings
import com.mangacombiner.util.AppVersionProvider
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.ProxyTestUtility
import com.mangacombiner.util.SlugUtils
import com.mangacombiner.util.UpdateDownloader
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.naturalSortComparator
import com.mangacombiner.util.titlecase
import com.mangacombiner.util.toSlug
import com.mangacombiner.util.SemanticVersion
import com.mangacombiner.util.restartApp
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

/**
 * Core services required for download operations
 */
data class DownloadDependencies(
    val downloadService: DownloadService,
    val scraperService: ScraperService,
    val backgroundDownloader: BackgroundDownloader,
    val cacheService: CacheService,
    val queuePersistenceService: QueuePersistenceService
)

/**
 * Repository dependencies for data persistence
 */
data class RepositoryDependencies(
    val settingsRepository: SettingsRepository,
    val readingProgressRepository: ReadingProgressRepository,
    val nsfwRepository: NsfwRepository,
    val safeOverrideRepository: SafeOverrideRepository
)

/**
 * Network and proxy related dependencies
 */
data class NetworkDependencies(
    val proxyMonitorService: ProxyMonitorService,
    val networkInterceptor: NetworkInterceptor,
    val webDavService: WebDavService
)

/**
 * Platform and utility dependencies
 */
data class PlatformDependencies(
    val clipboardManager: ClipboardManager,
    val platformProvider: PlatformProvider,
    val fileMover: FileMover,
    val epubReaderService: EpubReaderService,
    val updateService: UpdateService,
    val updateDownloader: UpdateDownloader,
    val appVersionProvider: AppVersionProvider
)

internal class JobEditedException : CancellationException("Job was edited and needs to be restarted.")

@OptIn(FlowPreview::class)
class MainViewModel(
    private val downloadDependencies: DownloadDependencies,
    private val repositoryDependencies: RepositoryDependencies,
    private val networkDependencies: NetworkDependencies,
    private val platformDependencies: PlatformDependencies
) : PlatformViewModel() {

    // Expose individual dependencies for backward compatibility
    internal val downloadService: DownloadService = downloadDependencies.downloadService
    internal val scraperService: ScraperService = downloadDependencies.scraperService
    internal val backgroundDownloader: BackgroundDownloader = downloadDependencies.backgroundDownloader
    internal val cacheService: CacheService = downloadDependencies.cacheService
    internal val queuePersistenceService: QueuePersistenceService = downloadDependencies.queuePersistenceService
    
    internal val settingsRepository: SettingsRepository = repositoryDependencies.settingsRepository
    internal val readingProgressRepository: ReadingProgressRepository = repositoryDependencies.readingProgressRepository
    internal val nsfwRepository: NsfwRepository = repositoryDependencies.nsfwRepository
    internal val safeOverrideRepository: SafeOverrideRepository = repositoryDependencies.safeOverrideRepository
    
    internal val proxyMonitorService: ProxyMonitorService = networkDependencies.proxyMonitorService
    internal val networkInterceptor: NetworkInterceptor = networkDependencies.networkInterceptor
    internal val webDavService: WebDavService = networkDependencies.webDavService
    
    internal val clipboardManager: ClipboardManager = platformDependencies.clipboardManager
    internal val platformProvider: PlatformProvider = platformDependencies.platformProvider
    internal val fileMover: FileMover = platformDependencies.fileMover
    internal val epubReaderService: EpubReaderService = platformDependencies.epubReaderService
    internal val updateService: UpdateService = platformDependencies.updateService
    internal val updateDownloader: UpdateDownloader = platformDependencies.updateDownloader
    internal val appVersionProvider: AppVersionProvider = platformDependencies.appVersionProvider

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
        val manuallyMarkedNsfw = nsfwRepository.loadNsfwPaths()
        val manuallyMarkedSafe = safeOverrideRepository.loadSafePaths()
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
                allowNsfw = savedSettings.allowNsfw,
                proxyEnabledOnStartup = savedSettings.proxyEnabledOnStartup,
                proxyConnectionState = ProxyMonitorService.ProxyConnectionState.UNKNOWN,
                ipLookupUrl = savedSettings.ipLookupUrl,
                libraryScanPaths = savedSettings.libraryScanPaths,
                manuallyMarkedNsfw = manuallyMarkedNsfw,
                manuallyMarkedSafe = manuallyMarkedSafe
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
        checkForUpdate()
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

    internal fun handleOfflineCheck(): Boolean {
        if (state.value.offlineMode) {
            _state.update { it.copy(completionMessage = "Action unavailable in Offline Mode.") }
            return true
        }
        return false
    }

    internal fun verifyProxyConnection() {
        if (handleOfflineCheck()) return

        initializeProxyVerification()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = state.value
                val url = buildProxyUrl(s.proxyType, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass)
                val lookupUrl = s.ipLookupUrl

                if (url == null) {
                    handleNoProxyConfigured()
                    return@launch
                }

                Logger.logInfo("Starting comprehensive proxy verification for: $url")
                val testResult = ProxyTestUtility.runComprehensiveProxyTest(url, lookupUrl)

                if (testResult.success) {
                    handleProxyVerificationSuccess(testResult)
                } else {
                    handleProxyVerificationFailure(testResult)
                }
            } catch (e: Exception) {
                handleProxyVerificationException(e)
            } finally {
                _state.update { it.copy(isInitialProxyCheckRunning = false) }
            }
        }
    }

    private fun initializeProxyVerification() {
        _state.update {
            it.copy(
                proxyStatus = ProxyStatus.VERIFYING,
                proxyVerificationMessage = "Running comprehensive proxy test...",
                ipInfoResult = null,
                ipCheckError = null,
                isNetworkBlocked = true // Block network while verifying
            )
        }
    }

    private fun handleNoProxyConfigured() {
        _state.update {
            it.copy(
                proxyStatus = ProxyStatus.UNVERIFIED,
                proxyVerificationMessage = "No proxy configured.",
                isNetworkBlocked = false // Unblock if no proxy is set
            )
        }
    }

    private fun handleProxyVerificationSuccess(testResult: ProxyTestUtility.ProxyTestResult) {
        val message = buildProxySuccessMessage(testResult)
        val ipInfo = createIpInfoFromTestResult(testResult)

        _state.update {
            it.copy(
                proxyStatus = ProxyStatus.CONNECTED,
                proxyVerificationMessage = message,
                ipInfoResult = ipInfo,
                isNetworkBlocked = false // Unblock on success
            )
        }

        restartProxyMonitoringIfNeeded()
    }

    private fun buildProxySuccessMessage(testResult: ProxyTestUtility.ProxyTestResult): String {
        return buildString {
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
    }

    private fun createIpInfoFromTestResult(testResult: ProxyTestUtility.ProxyTestResult): IpInfo {
        return IpInfo(
            ip = testResult.proxyIp,
            city = testResult.proxyLocation?.split(", ")?.getOrNull(0),
            country = testResult.proxyLocation?.split(", ")?.getOrNull(1)
        )
    }

    private fun handleProxyVerificationFailure(testResult: ProxyTestUtility.ProxyTestResult) {
        val message = buildProxyFailureMessage(testResult)

        _state.update {
            it.copy(
                proxyStatus = ProxyStatus.FAILED,
                proxyVerificationMessage = message,
                isNetworkBlocked = true // Keep blocked on failure
            )
        }
    }

    private fun buildProxyFailureMessage(testResult: ProxyTestUtility.ProxyTestResult): String {
        return buildString {
            append("✗ Proxy test failed")
            testResult.error?.let { append(": $it") }
            if (!testResult.ipChanged && testResult.directIp != null && testResult.proxyIp != null) {
                append("\n⚠️ IP unchanged - proxy may not be working")
            }
            if (!testResult.killSwitchWorking) {
                append("\n⚠️ Kill switch not working - traffic may leak!")
            }
        }
    }

    private fun handleProxyVerificationException(e: Exception) {
        val message = "Test failed: ${e.message?.take(100) ?: "Unknown error"}"
        _state.update {
            it.copy(
                proxyStatus = ProxyStatus.FAILED,
                proxyVerificationMessage = message,
                isNetworkBlocked = true // Keep blocked on failure
            )
        }
        Logger.logError("Comprehensive proxy verification failed", e)
    }

    internal fun fetchChapters() {
        if (handleOfflineCheck()) return
        val url = _state.value.seriesUrl
        if (url.isBlank()) return

        fetchChaptersJob?.cancel()
        _state.update { it.copy(isFetchingChapters = true) }

        fetchChaptersJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                networkInterceptor.checkNetworkAllowed() // Check kill switch

                val s = _state.value
                val contextData = prepareFetchContext(s, url)
                val (seriesMetadata, chapters) = performChapterFetch(s, url)

                if (seriesMetadata != null && chapters.isNotEmpty()) {
                    val processedChapters = processChapters(chapters, contextData)
                    updateStateWithFetchedChapters(seriesMetadata, processedChapters)
                } else {
                    Logger.logError("Could not find any chapters at the provided URL or the series was filtered.")
                }
            } catch (e: ProxyKillSwitchException) {
                handleKillSwitchError(e)
            } catch (e: NetworkException) {
                handleNetworkError(e)
            } catch (e: Exception) {
                handleGenericFetchError(e)
            } finally {
                _state.update { it.copy(isFetchingChapters = false) }
            }
        }
    }

    private data class FetchContext(
        val seriesSlug: String,
        val cachedChapterStatus: Map<String, Boolean>,
        val localChapterMap: Map<String, String>,
        val failedChapterTitles: Set<String>,
        val preselectedNames: Set<String>
    )

    private fun prepareFetchContext(s: UiState, url: String): FetchContext {
        val seriesSlug = url.toSlug()
        return FetchContext(
            seriesSlug = seriesSlug,
            cachedChapterStatus = cacheService.getCachedChapterStatus(seriesSlug),
            localChapterMap = s.localChaptersForSync,
            failedChapterTitles = s.failedItemsForSync.keys.map { SlugUtils.toComparableKey(it) }.toSet(),
            preselectedNames = s.chaptersToPreselect
        )
    }

    private suspend fun performChapterFetch(
        s: UiState,
        url: String
    ): Pair<com.mangacombiner.model.SeriesMetadata?, List<Pair<String, String>>> {
        val client = createHttpClient(buildProxyUrl(s.proxyType, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass))
        Logger.logInfo("Fetching chapter list for: $url")
        val userAgent = UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first()
        val result = scraperService.fetchSeriesDetails(client, url, userAgent, s.allowNsfw)
        client.close()
        return result
    }

    private fun processChapters(
        chapters: List<Pair<String, String>>,
        context: FetchContext
    ): List<Chapter> {
        return chapters.map { (chapUrl, title) ->
            createChapterFromData(chapUrl, title, context)
        }.sortedWith(compareBy(naturalSortComparator) { it.title })
    }

    private fun createChapterFromData(chapUrl: String, title: String, context: FetchContext): Chapter {
        val sanitizedTitle = FileUtils.sanitizeFilename(title)
        val comparableKey = SlugUtils.toComparableKey(title)
        val originalLocalSlug = context.localChapterMap[comparableKey]

        val isLocal = originalLocalSlug != null
        val isCached = context.cachedChapterStatus.containsKey(sanitizedTitle)
        val isBroken = isCached && context.cachedChapterStatus[sanitizedTitle] == false
        val isRetry = comparableKey in context.failedChapterTitles

        val sources = buildChapterSources(isLocal, isCached)
        val chapter = createBaseChapter(chapUrl, title, sources, originalLocalSlug, isRetry, isBroken)
        val initialSource = determineInitialSource(sanitizedTitle, context, isBroken, isRetry, chapter)

        return chapter.copy(selectedSource = initialSource)
    }

    private fun buildChapterSources(isLocal: Boolean, isCached: Boolean): MutableSet<ChapterSource> {
        val sources = mutableSetOf(ChapterSource.WEB)
        if (isLocal) sources.add(ChapterSource.LOCAL)
        if (isCached) sources.add(ChapterSource.CACHE)
        return sources
    }

    private fun createBaseChapter(
        chapUrl: String,
        title: String,
        sources: MutableSet<ChapterSource>,
        originalLocalSlug: String?,
        isRetry: Boolean,
        isBroken: Boolean
    ): Chapter {
        return Chapter(
            url = chapUrl,
            title = title,
            availableSources = sources,
            selectedSource = null,
            localSlug = originalLocalSlug,
            isRetry = isRetry,
            isBroken = isBroken
        )
    }

    private fun determineInitialSource(
        sanitizedTitle: String,
        context: FetchContext,
        isBroken: Boolean,
        isRetry: Boolean,
        chapter: Chapter
    ): ChapterSource? {
        return when {
            sanitizedTitle in context.preselectedNames -> {
                if (isBroken) ChapterSource.WEB else ChapterSource.CACHE
            }
            context.preselectedNames.isNotEmpty() -> null
            isRetry || isBroken -> ChapterSource.WEB
            else -> getChapterDefaultSource(chapter)
        }
    }

    private fun updateStateWithFetchedChapters(
        seriesMetadata: com.mangacombiner.model.SeriesMetadata,
        processedChapters: List<Chapter>
    ) {
        _state.update {
            it.copy(
                seriesMetadata = seriesMetadata,
                customTitle = seriesMetadata.title,
                fetchedChapters = processedChapters,
                showChapterDialog = true,
                chaptersToPreselect = emptySet()
            )
        }
    }

    private fun handleKillSwitchError(e: ProxyKillSwitchException) {
        Logger.logError("Kill switch blocked chapter fetching: ${e.message}")
        _state.update {
            it.copy(
                showNetworkErrorDialog = true,
                networkErrorMessage = "Network operations are blocked. Proxy connection required."
            )
        }
    }

    private fun handleNetworkError(e: NetworkException) {
        Logger.logError("Failed to fetch chapters due to network error", e)
        _state.update {
            it.copy(
                showNetworkErrorDialog = true,
                networkErrorMessage = "Chapter fetching failed. Please check your network connection and proxy settings."
            )
        }
    }

    private fun handleGenericFetchError(e: Exception) {
        if (e !is CancellationException) {
            Logger.logError("Failed to fetch chapters", e)
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
        setupLogListener()
        setupOperationStateListener()
        setupSettingsListener()
        setupDownloadQueueListener()
        setupJobStatusListener()
        setupQueueProcessorListener()
        setupQueuePersistenceListener()
        Logger.logDebug { "ViewModel listeners set up." }
    }

    private fun setupLogListener() {
        Logger.addListener { logMessage ->
            _logs.update { it + logMessage }
        }
    }

    private fun setupOperationStateListener() {
        viewModelScope.launch {
            _operationState.collect {
                _state.update { uiState -> uiState.copy(operationState = it) }
            }
        }
    }

    private fun setupSettingsListener() {
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
    }

    private fun setupDownloadQueueListener() {
        viewModelScope.launch {
            state.map { it.downloadQueue }.collect {
                updateOverallProgress()
            }
        }
    }

    private fun setupJobStatusListener() {
        viewModelScope.launch {
            backgroundDownloader.jobStatusFlow.collect { update ->
                handleJobStatusUpdate(update)
            }
        }
    }

    private fun setupQueueProcessorListener() {
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
                .collect { processorState ->
                    processQueueState(processorState)
                }
        }
    }

    private fun processQueueState(processorState: QueueProcessorState) {
        val (batchWorkers, queue, isPaused, isBlocked, isVerifying, proxyState) = processorState
        
        val shouldStopAll = isPaused || isBlocked || isVerifying ||
            (state.value.proxyEnabledOnStartup && proxyState == ProxyMonitorService.ProxyConnectionState.DISCONNECTED)

        if (shouldStopAll) {
            handleStopAllJobs(isPaused, isBlocked, isVerifying, proxyState)
            return
        }

        val jobManagement = calculateJobChanges(queue, batchWorkers)
        if (jobManagement.hasChanges()) {
            applyJobChanges(jobManagement)
        }
    }

    private fun handleStopAllJobs(
        isPaused: Boolean,
        isBlocked: Boolean,
        isVerifying: Boolean,
        proxyState: ProxyMonitorService.ProxyConnectionState
    ) {
        if (activeServiceJobs.isNotEmpty()) {
            Logger.logDebug { "Stopping all active jobs due to: paused=$isPaused, blocked=$isBlocked, verifying=$isVerifying, killSwitch=${proxyState == ProxyMonitorService.ProxyConnectionState.DISCONNECTED}" }
            backgroundDownloader.stopAllJobs()
            activeServiceJobs.clear()
        }
    }

    private data class JobManagement(
        val jobsToStop: Set<String>,
        val jobsToStart: Set<String>
    ) {
        fun hasChanges(): Boolean = jobsToStop.isNotEmpty() || jobsToStart.isNotEmpty()
    }

    private fun calculateJobChanges(queue: List<DownloadJob>, batchWorkers: Int): JobManagement {
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

        return JobManagement(jobsToStop, jobsToStart)
    }

    private fun applyJobChanges(jobManagement: JobManagement) {
        jobManagement.jobsToStop.forEach { jobId ->
            Logger.logDebug { "Throttling job due to lower priority: $jobId" }
            backgroundDownloader.stopJob(jobId)
            activeServiceJobs.remove(jobId)
        }

        jobManagement.jobsToStart.forEach { jobId ->
            Logger.logDebug { "Activating job due to high priority: $jobId" }
            startJob(jobId)
        }

        updateQueueStatusAfterJobChanges(jobManagement)
    }

    private fun updateQueueStatusAfterJobChanges(jobManagement: JobManagement) {
        _state.update { currentState ->
            val newQueue = currentState.downloadQueue.map { job ->
                when (job.id) {
                    in jobManagement.jobsToStop -> job.copy(status = "Paused")
                    in jobManagement.jobsToStart -> {
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

    private fun setupQueuePersistenceListener() {
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

                if (update.status == "Cancelled" && (state.isQueueGloballyPaused || job.isIndividuallyPaused || job.status == "Paused")) {
                    return@map job.copy(status = "Paused")
                }

                val newStatus = update.status ?: job.status
                var newDownloadedChapters = job.downloadedChapters
                var newProgress = job.progress

                if (update.downloadedChapters != null) {
                    val isStarting = job.status in listOf("Queued", "Waiting...", "Starting...")
                    newDownloadedChapters = if (isStarting) {
                        update.downloadedChapters
                    } else {
                        job.downloadedChapters + update.downloadedChapters
                    }
                }

                if (newStatus == "Completed") {
                    newDownloadedChapters = job.totalChapters
                    newProgress = 1f
                } else if (update.downloadedChapters != null) {
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

    private fun changeChapter(delta: Int) {
        val book = state.value.currentBook ?: return
        val currentChapterIndex = state.value.currentChapterIndex
        val newChapterIndex = (currentChapterIndex + delta).coerceIn(0, book.chapters.lastIndex)

        if (currentChapterIndex != newChapterIndex) {
            var pageCounter = 0
            for (i in 0 until newChapterIndex) {
                pageCounter += book.chapters[i].imageHrefs.size.coerceAtLeast(if (book.chapters[i].textContent != null) 1 else 0)
            }
            val newPage = pageCounter + 1

            readingProgressRepository.saveProgress(book.filePath, newPage)
            _state.update {
                it.copy(
                    currentPageInBook = newPage,
                    currentChapterIndex = newChapterIndex
                )
            }
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
            is Event.Library -> handleLibraryEvent(event)
            is Event.Navigate -> _state.update { it.copy(currentScreen = event.screen) }
            is Event.ToggleAboutDialog -> _state.update { it.copy(showAboutDialog = event.show) }
            is Event.CheckForUpdate -> checkForUpdate()
            is Event.ToggleUpdateDialog -> _state.update { it.copy(showUpdateDialog = event.show) }
            is Event.DownloadUpdate -> downloadUpdate()
            is Event.ToggleUpdateDownloadedDialog -> _state.update { it.copy(showUpdateDownloadedDialog = event.show) }
            is Event.RestartToUpdate -> restartToUpdate()
        }
    }

    private fun restartToUpdate() {
        val updatePath = _state.value.downloadedUpdatePath ?: return
        restartApp(updatePath)
    }

    private fun downloadUpdate() {
        val release = _state.value.latestRelease ?: return
        val asset = release.assets.find { it.name.endsWith(".apk") || it.name.endsWith(".jar") } ?: return
        updateDownloader.downloadAndInstall(asset.browserDownloadUrl) { path ->
            _state.update { it.copy(showUpdateDownloadedDialog = true, downloadedUpdatePath = path) }
        }
        _state.update { it.copy(showUpdateDialog = false) }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            val latestRelease = updateService.getLatestRelease()
            val currentVersion = appVersionProvider.getAppVersion()
            if (latestRelease != null) {
                val latestVersion = SemanticVersion(latestRelease.tagName.removePrefix("v"))
                val current = SemanticVersion(currentVersion.removePrefix("v"))
                if (latestVersion > current) {
                    _state.update {
                        it.copy(
                            latestRelease = latestRelease,
                            showUpdateDialog = true
                        )
                    }
                }
            }
        }
    }

    private fun handleLibraryEvent(event: Event.Library) {
        when (event) {
            is Event.Library.ScanForBooks -> scanForLibraryBooks()
            is Event.Library.OpenFileDirectly -> handleOpenFileDirectly()
            is Event.Library.OpenBook -> viewModelScope.launch { openBook(event.bookPath) }
            is Event.Library.CloseBook -> closeBook()
            is Event.Library.NextChapter -> changeChapter(1)
            is Event.Library.PreviousChapter -> changeChapter(-1)
            is Event.Library.ChangeReaderTheme -> _state.update { it.copy(readerTheme = event.theme) }
            is Event.Library.GoToPage -> handleGoToPage(event.page)
            is Event.Library.ZoomIn -> handleZoomIn()
            is Event.Library.ZoomOut -> handleZoomOut()
            is Event.Library.ResetZoom -> _state.update { it.copy(readerFontSize = 16.0f, readerImageScale = 1.0f) }
            is Event.Library.UpdateProgress -> handleUpdateProgress(event)
            is Event.Library.ToggleToc -> _state.update { it.copy(showReaderToc = !it.showReaderToc) }
            is Event.Library.UpdateSearchQuery -> _state.update { it.copy(librarySearchQuery = event.query) }
            is Event.Library.SetSort -> _state.update { it.copy(librarySortOption = event.sortOption) }
            is Event.Library.EditBook -> handleEditBook(event.bookPath)
            is Event.Library.RequestDeleteBook -> _state.update { it.copy(showDeleteConfirmationDialog = true, bookToModify = event.bookPath) }
            is Event.Library.ConfirmDeleteBook -> handleConfirmDeleteBook()
            is Event.Library.CancelDeleteBook -> _state.update { it.copy(showDeleteConfirmationDialog = false, bookToModify = null) }
            is Event.Library.ToggleNsfw -> onToggleNsfw(event.bookPath)
            is Event.Library.ToggleSafeOverride -> onToggleSafeOverride(event.bookPath)
        }
    }

    private fun handleOpenFileDirectly() {
        viewModelScope.launch {
            _state.update { it.copy(filePickerPurpose = FilePickerRequest.FilePurpose.OPEN_DIRECTLY) }
            _filePickerRequest.emit(FilePickerRequest.OpenFile(FilePickerRequest.FilePurpose.OPEN_DIRECTLY))
        }
    }

    private fun handleGoToPage(page: Int) {
        val book = _state.value.currentBook ?: return
        val newChapterIndex = findChapterIndexForPage(book, page)

        readingProgressRepository.saveProgress(book.filePath, page)
        _state.update {
            it.copy(
                currentPageInBook = page,
                currentChapterIndex = newChapterIndex
            )
        }
    }

    private fun findChapterIndexForPage(book: com.mangacombiner.service.Book, targetPage: Int): Int {
        var pagesCounted = 0
        for ((idx, chap) in book.chapters.withIndex()) {
            val chapterSize = chap.imageHrefs.size.coerceAtLeast(if (chap.textContent != null) 1 else 0)
            if (targetPage > pagesCounted && targetPage <= pagesCounted + chapterSize) {
                return idx
            }
            pagesCounted += chapterSize
        }
        return 0
    }

    private fun handleZoomIn() {
        _state.update {
            if (it.isCurrentPageText) {
                it.copy(readerFontSize = (it.readerFontSize + 1.0f).coerceIn(8.0f, 48.0f))
            } else {
                it.copy(readerImageScale = (it.readerImageScale + 0.2f).coerceIn(0.1f, 3.0f))
            }
        }
    }

    private fun handleZoomOut() {
        _state.update {
            if (it.isCurrentPageText) {
                it.copy(readerFontSize = (it.readerFontSize - 1.0f).coerceIn(8.0f, 48.0f))
            } else {
                it.copy(readerImageScale = (it.readerImageScale - 0.2f).coerceIn(0.1f, 3.0f))
            }
        }
    }

    private fun handleUpdateProgress(event: Event.Library.UpdateProgress) {
        _state.value.currentBook?.let { book ->
            readingProgressRepository.saveProgress(book.filePath, event.currentPage)
        }
        _state.update { 
            it.copy(
                currentPageInBook = event.currentPage,
                currentChapterIndex = event.currentChapterIndex,
                isCurrentPageText = event.isTextPage
            )
        }
    }

    private fun handleEditBook(bookPath: String) {
        _state.update { it.copy(currentScreen = Screen.DOWNLOAD) }
        analyzeLocalFile(File(bookPath))
    }

    private fun handleConfirmDeleteBook() {
        val bookPath = state.value.bookToModify
        _state.update { it.copy(showDeleteConfirmationDialog = false, bookToModify = null) }
        if (bookPath != null) {
            viewModelScope.launch(Dispatchers.IO) {
                if (fileMover.deleteFile(bookPath)) {
                    Logger.logInfo("Successfully deleted file: $bookPath")
                    scanForLibraryBooks() // Refresh library
                } else {
                    Logger.logError("Failed to delete file: $bookPath")
                }
            }
        }
    }

    private fun onToggleNsfw(bookPath: String) {
        val currentNsfwPaths = state.value.manuallyMarkedNsfw
        val isCurrentlyMarked = bookPath in currentNsfwPaths
        val newNsfwState = !isCurrentlyMarked

        nsfwRepository.setNsfw(bookPath, newNsfwState)

        val updatedNsfwPaths = if (newNsfwState) {
            currentNsfwPaths + bookPath
        } else {
            currentNsfwPaths - bookPath
        }

        // If we're marking as NSFW, remove any safe override.
        var updatedSafePaths = state.value.manuallyMarkedSafe
        if (newNsfwState && updatedSafePaths.contains(bookPath)) {
            safeOverrideRepository.setSafe(bookPath, false)
            updatedSafePaths = updatedSafePaths - bookPath
            Logger.logDebug { "Removed safe override for '${File(bookPath).name}' because it was marked as NSFW." }
        }

        _state.update { it.copy(manuallyMarkedNsfw = updatedNsfwPaths, manuallyMarkedSafe = updatedSafePaths) }
        Logger.logInfo("Marked '${File(bookPath).name}' as ${if (newNsfwState) "NSFW" else "SFW"}.")
    }

    private fun onToggleSafeOverride(bookPath: String) {
        val currentSafePaths = state.value.manuallyMarkedSafe
        val isCurrentlyMarked = bookPath in currentSafePaths
        val newSafeState = !isCurrentlyMarked

        safeOverrideRepository.setSafe(bookPath, newSafeState)

        val updatedSafePaths = if (newSafeState) {
            currentSafePaths + bookPath
        } else {
            currentSafePaths - bookPath
        }

        // If we're marking as safe, remove any manual NSFW flag.
        var updatedNsfwPaths = state.value.manuallyMarkedNsfw
        if (newSafeState && updatedNsfwPaths.contains(bookPath)) {
            nsfwRepository.setNsfw(bookPath, false)
            updatedNsfwPaths = updatedNsfwPaths - bookPath
            Logger.logDebug { "Removed manual NSFW flag for '${File(bookPath).name}' because it was marked as safe." }
        }

        _state.update { it.copy(manuallyMarkedSafe = updatedSafePaths, manuallyMarkedNsfw = updatedNsfwPaths) }
        Logger.logInfo("Marked '${File(bookPath).name}' with a safe override: $newSafeState.")
    }

    fun onFileSelected(path: String) {
        Logger.logDebug { "File selected: $path" }
        when (state.value.filePickerPurpose) {
            FilePickerRequest.FilePurpose.OPEN_DIRECTLY -> {
                viewModelScope.launch { openBook(path) }
            }
            FilePickerRequest.FilePurpose.UPDATE_LOCAL -> {
                startFromFile(path)
            }
            null -> Logger.logWarn("File selected but no purpose was set.")
        }
        _state.update { it.copy(filePickerPurpose = null) }
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
            FilePickerRequest.PathType.LIBRARY_SCAN_ADD -> {
                _state.update { it.copy(libraryScanPaths = it.libraryScanPaths + path) }
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
                initializeOperation()
                val operationContext = prepareOperationContext(isRetry)
                
                if (operationContext.chaptersForOperation.isEmpty()) {
                    Logger.logError("No chapters selected for operation. Aborting.")
                    return@launch
                }

                val allChapterFolders = processAllChapterSources(operationContext, isRetry)
                
                coroutineContext.ensureActive()
                packageFinalFile(allChapterFolders)

            } catch (e: CancellationException) {
                handleOperationCancellation()
                throw e
            } finally {
                finalizeOperation()
            }
        }
    }

    private data class OperationContext(
        val chaptersForOperation: List<Chapter>,
        val tempDir: File,
        val tempUpdateDir: File,
        val tempSeriesDir: File,
        val s: UiState
    )

    private fun initializeOperation() {
        _state.update { 
            it.copy(
                operationState = OperationState.RUNNING, 
                progress = 0f, 
                progressStatusText = "Starting operation..."
            ) 
        }
    }

    private fun prepareOperationContext(isRetry: Boolean): OperationContext {
        val s = _state.value
        val tempDir = File(platformProvider.getTmpDir())
        val tempUpdateDir = File(tempDir, "manga-update-${System.currentTimeMillis()}").apply { mkdirs() }
        
        val chaptersForOperation = if (isRetry) {
            val failedTitles = s.lastDownloadResult?.failedChapters?.keys ?: emptySet()
            s.fetchedChapters.filter { it.title in failedTitles }
        } else {
            s.fetchedChapters.filter { it.selectedSource != null }
        }

        val seriesSlug = if (s.seriesUrl.isNotBlank()) s.seriesUrl.toSlug() else ""
        val tempSeriesDir = if (seriesSlug.isNotBlank()) {
            File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }
        } else {
            tempDir
        }

        writeSeriesUrlIfNeeded(s, tempSeriesDir)

        return OperationContext(chaptersForOperation, tempDir, tempUpdateDir, tempSeriesDir, s)
    }

    private fun writeSeriesUrlIfNeeded(s: UiState, tempSeriesDir: File) {
        if (s.seriesUrl.isNotBlank() && tempSeriesDir.name.startsWith("manga-dl-")) {
            File(tempSeriesDir, "url.txt").writeText(s.seriesUrl)
            Logger.logDebug { "Wrote series URL to ${tempSeriesDir.name}/url.txt" }
        }
    }

    private suspend fun processAllChapterSources(
        context: OperationContext,
        isRetry: Boolean
    ): MutableList<File> {
        val allChapterFolders = initializeChapterFolders(context.s, isRetry)
        
        if (!isRetry) {
            processLocalChapters(context, allChapterFolders)
            processCachedChapters(context, allChapterFolders)
        }

        processWebChapters(context, allChapterFolders)
        
        return allChapterFolders
    }

    private fun initializeChapterFolders(s: UiState, isRetry: Boolean): MutableList<File> {
        return if (isRetry) {
            s.lastDownloadResult?.successfulFolders?.toMutableList() ?: mutableListOf()
        } else {
            mutableListOf()
        }
    }

    private suspend fun processLocalChapters(
        context: OperationContext,
        allChapterFolders: MutableList<File>
    ) {
        val chaptersToExtract = context.chaptersForOperation.filter { it.selectedSource == ChapterSource.LOCAL }
        if (context.s.sourceFilePath != null && chaptersToExtract.isNotEmpty()) {
            _state.update { it.copy(progress = 0.1f, progressStatusText = "Extracting local chapters...") }
            allChapterFolders.addAll(
                downloadService.processorService.extractChaptersToDirectory(
                    File(context.s.sourceFilePath!!),
                    chaptersToExtract.mapNotNull { it.localSlug },
                    context.tempUpdateDir
                )
            )
        }
    }

    private fun processCachedChapters(
        context: OperationContext,
        allChapterFolders: MutableList<File>
    ) {
        val chaptersFromCache = context.chaptersForOperation.filter { it.selectedSource == ChapterSource.CACHE }
        chaptersFromCache.forEach {
            allChapterFolders.add(File(context.tempSeriesDir, FileUtils.sanitizeFilename(it.title)))
        }
    }

    private suspend fun processWebChapters(
        context: OperationContext,
        allChapterFolders: MutableList<File>
    ) {
        val chaptersToDownload = context.chaptersForOperation.filter { it.selectedSource == ChapterSource.WEB }
        if (chaptersToDownload.isNotEmpty()) {
            val downloadOptions = createDownloadOptions(context.s, context.s.seriesUrl, chaptersToDownload)
            _state.update { it.copy(activeDownloadOptions = downloadOptions) }
            val downloadResult = downloadService.downloadChapters(downloadOptions, context.tempSeriesDir)

            downloadResult?.let { result ->
                allChapterFolders.addAll(result.successfulFolders)
                _state.update { it.copy(lastDownloadResult = result) }

                if (result.failedChapters.isNotEmpty()) {
                    _state.update { it.copy(showBrokenDownloadDialog = true) }
                    return
                }
            }
        }
    }

    private fun handleOperationCancellation() {
        Logger.logInfo("Operation cancelled by user.")
        if (_state.value.deleteCacheOnCancel) {
            deleteCacheOnCancellation()
        }
    }

    private fun deleteCacheOnCancellation() {
        val seriesSlug = if (_state.value.seriesUrl.isNotBlank()) _state.value.seriesUrl.toSlug() else ""
        if (seriesSlug.isNotBlank()) {
            val tempSeriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")
            if (tempSeriesDir.exists()) {
                Logger.logInfo("Deleting temporary files for cancelled job...")
                tempSeriesDir.deleteRecursively()
            }
        }
    }

    private suspend fun finalizeOperation() {
        withContext(NonCancellable) {
            _state.update { it.copy(operationState = OperationState.IDLE) }
            if (!_state.value.showCompletionDialog && !_state.value.showBrokenDownloadDialog) {
                resetUiStateAfterOperation()
            }
            activeOperationJob = null
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
                ProcessorService.EpubCreationOptions(
                    seriesUrl = s.seriesUrl,
                    failedChapters = failedChapters,
                    seriesMetadata = s.seriesMetadata
                )
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
        _state.update { it.copy(operationState = OperationState.IDLE) }
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
            allowNsfw = s.allowNsfw,
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
