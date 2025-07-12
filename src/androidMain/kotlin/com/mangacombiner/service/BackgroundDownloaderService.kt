package com.mangacombiner.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mangacombiner.R
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.toSlug
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class BackgroundDownloaderService : Service(), KoinComponent {

    private lateinit var downloadService: DownloadService
    private lateinit var fileMover: FileMover
    private lateinit var platformProvider: PlatformProvider
    private lateinit var queuePersistenceService: QueuePersistenceService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        downloadService = get()
        fileMover = get()
        platformProvider = get()
        queuePersistenceService = get()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Logger.logDebug { "BackgroundDownloaderService instance created by OS." }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_JOB -> handleStartJob(intent)
            ACTION_STOP_JOB -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId != null) {
                    stopJob(jobId)
                }
            }
            ACTION_STOP_ALL -> stopAllJobs()
        }

        // If no jobs are running after a command, the service can stop.
        if (runningJobs.isEmpty()) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun handleStartJob(intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID)
        if (jobId == null) {
            Logger.logError("Service received START_JOB with no JOB_ID. Stopping.")
            return
        }

        if (runningJobs.containsKey(jobId)) {
            Logger.logDebug { "Job $jobId is already running. Ignoring command." }
            return
        }

        val seriesPath = intent.getStringExtra(EXTRA_SERIES_PATH) ?: ""
        val op = queuePersistenceService.loadOperationMetadata(seriesPath)

        if (op == null) {
            Logger.logError("Could not load QueuedOperation for job $jobId. Stopping.")
            JobStatusHolder.postUpdate(JobStatusUpdate(jobId, errorMessage = "Operation context not found", isFinished = true))
            return
        }

        val job = serviceScope.launch {
            try {
                acquireWakeLock(op.customTitle)
                runQueuedOperation(op)
            } finally {
                releaseWakeLock()
                runningJobs.remove(jobId)
                Logger.logDebug { "Coroutine for job $jobId finished. Running jobs: ${runningJobs.size}" }
                if (runningJobs.isEmpty()) {
                    stopSelf()
                }
            }
        }
        runningJobs[jobId] = job
    }

    private fun stopJob(jobId: String) {
        runningJobs[jobId]?.cancel(CancellationException("Job was stopped by the queue manager."))
        runningJobs.remove(jobId)
        Logger.logDebug { "Stopped job in service: $jobId" }
    }

    private fun stopAllJobs() {
        runningJobs.keys.forEach { stopJob(it) }
        Logger.logDebug { "Stopped all jobs in service." }
    }

    private suspend fun runQueuedOperation(op: QueuedOperation) {
        try {
            val initialNotification = createNotification("Starting: ${op.customTitle}", 0, 0, true)
            startForeground(NOTIFICATION_ID, initialNotification)

            val chaptersFromCache = op.chapters.filter { it.selectedSource == ChapterSource.CACHE }
            JobStatusHolder.postUpdate(JobStatusUpdate(op.jobId, downloadedChapters = chaptersFromCache.size))

            Logger.logInfo("--- Starting BG Job: ${op.customTitle} (${op.jobId}) ---")
            JobStatusHolder.postUpdate(JobStatusUpdate(op.jobId, "Starting...", 0.05f))

            val tempDir = File(platformProvider.getTmpDir())
            val seriesSlug = op.seriesUrl.toSlug()
            val tempSeriesDir = File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }

            if (op.seriesUrl.isNotBlank()) {
                File(tempSeriesDir, "url.txt").writeText(op.seriesUrl)
            }

            val chaptersToDownload = op.chapters.filter { it.selectedSource == ChapterSource.WEB }
            val allChapterFolders = chaptersFromCache
                .map { File(tempSeriesDir, FileUtils.sanitizeFilename(it.title)) }
                .toMutableList()

            var downloadResult: DownloadResult? = null

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
                    isPaused = { false },
                    dryRun = false,
                    onProgressUpdate = { chapterProgress, status ->
                        val update = JobStatusUpdate(op.jobId, status = status, progress = chapterProgress)
                        JobStatusHolder.postUpdate(update)
                        notificationManager.notify(NOTIFICATION_ID, createNotification(status, (chapterProgress * 100).toInt(), 100))
                    },
                    onChapterCompleted = { completedChapterUrl ->
                        JobStatusHolder.postUpdate(JobStatusUpdate(op.jobId, downloadedChapters = 1))
                        val currentOp = queuePersistenceService.loadOperationMetadata(tempSeriesDir.absolutePath) ?: op
                        val updatedChapters = currentOp.chapters.map { chapter ->
                            if (chapter.url == completedChapterUrl) {
                                chapter.copy(availableSources = chapter.availableSources + ChapterSource.CACHE)
                            } else {
                                chapter
                            }
                        }
                        queuePersistenceService.saveOperationMetadata(currentOp.copy(chapters = updatedChapters))
                    }
                )
                downloadResult = downloadService.downloadChapters(downloadOptions, tempSeriesDir)
                downloadResult?.successfulFolders?.let { allChapterFolders.addAll(it) }
            }

            JobStatusHolder.postUpdate(JobStatusUpdate(op.jobId, status = "Packaging..."))
            notificationManager.notify(NOTIFICATION_ID, createNotification("Packaging: ${op.customTitle}", 0, 0, true))

            val finalFileName = "${FileUtils.sanitizeFilename(op.customTitle)}.${op.outputFormat}"
            val tempOutputFile = File(tempDir, finalFileName)

            if (op.outputFormat == "cbz") {
                downloadService.processorService.createCbzFromFolders(op.customTitle, allChapterFolders, tempOutputFile, op.seriesUrl, downloadResult?.failedChapters)
            } else {
                downloadService.processorService.createEpubFromFolders(op.customTitle, allChapterFolders, tempOutputFile, op.seriesUrl, downloadResult?.failedChapters)
            }

            fileMover.moveToFinalDestination(tempOutputFile, op.outputPath, finalFileName)
            JobStatusHolder.postUpdate(JobStatusUpdate(op.jobId, status = "Completed", progress = 1f, isFinished = true))
            Logger.logInfo("--- Finished BG Job: ${op.customTitle} ---")

            notificationManager.notify(NOTIFICATION_ID_COMPLETED_OFFSET + op.jobId.hashCode(), createNotification("Completed: ${op.customTitle}", 0, 0, isOngoing = false))
            stopForeground(STOP_FOREGROUND_REMOVE)

        } catch (e: CancellationException) {
            JobStatusHolder.postUpdate(JobStatusUpdate(op.jobId, status = "Cancelled", isFinished = true))
            Logger.logInfo("Job ${op.jobId} was cancelled.")
        } catch (e: Exception) {
            val errorMessage = "Error: ${e.message?.take(40) ?: "Unknown"}"
            Logger.logError("Job ${op.jobId} failed", e)
            JobStatusHolder.postUpdate(JobStatusUpdate(op.jobId, status = errorMessage, isFinished = true, errorMessage = e.message))
        }
    }

    private fun acquireWakeLock(jobTitle: String) {
        wakeLock?.release()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MangaCombiner::DownloadWakelock").apply {
                Logger.logDebug { "Acquiring WakeLock for job: $jobTitle" }
                acquire(30 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            Logger.logDebug { "Releasing WakeLock." }
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val name = "Download Service"
        val descriptionText = "Shows manga download progress"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String, progress: Int, maxProgress: Int, isIndeterminate: Boolean = false, isOngoing: Boolean = true): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Combiner Download")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOnlyAlertOnce(true)
            .setOngoing(isOngoing)
            .setProgress(maxProgress, progress, isIndeterminate)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        Logger.logDebug { "BackgroundDownloaderService destroyed." }
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_JOB = "com.mangacombiner.action.START_JOB"
        const val ACTION_STOP_JOB = "com.mangacombiner.action.STOP_JOB"
        const val ACTION_STOP_ALL = "com.mangacombiner.action.STOP_ALL"
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_SERIES_PATH = "extra_series_path"
        private const val CHANNEL_ID = "MangaDownloaderChannel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ID_COMPLETED_OFFSET = 1000
    }
}
