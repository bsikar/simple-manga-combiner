package com.mangacombiner.service

import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.toSlug
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class DesktopDownloader(
    private val downloadService: DownloadService,
    private val fileMover: FileMover,
    private val platformProvider: PlatformProvider,
    private val queuePersistenceService: QueuePersistenceService
) : BackgroundDownloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<String, Job>()

    private val _jobStatusFlow = MutableSharedFlow<JobStatusUpdate>(extraBufferCapacity = 128)
    override val jobStatusFlow = _jobStatusFlow.asSharedFlow()

    override fun startJob(op: QueuedOperation) {
        if (runningJobs.containsKey(op.jobId)) {
            Logger.logDebug { "Job ${op.jobId} is already running in DesktopDownloader." }
            return
        }

        // Persist metadata so it can be reloaded if the app restarts
        queuePersistenceService.saveOperationMetadata(op)

        val job = scope.launch {
            try {
                runQueuedOperation(op)
            } finally {
                runningJobs.remove(op.jobId)
                Logger.logDebug { "Desktop coroutine for job ${op.jobId} finished. Running jobs: ${runningJobs.size}" }
            }
        }
        runningJobs[op.jobId] = job
        job.invokeOnCompletion { runningJobs.remove(op.jobId) }
    }

    override fun stopJob(jobId: String) {
        runningJobs[jobId]?.cancel(CancellationException("Job stopped by queue manager"))
        runningJobs.remove(jobId)
        Logger.logDebug { "[DesktopDownloader] Stopped job $jobId" }
    }



    override fun stopAllJobs() {
        runningJobs.keys.forEach { stopJob(it) }
        Logger.logDebug { "[DesktopDownloader] Stopped all jobs." }
    }

    private suspend fun runQueuedOperation(op: QueuedOperation) {
        try {
            val chaptersFromCache = op.chapters.filter { it.selectedSource == ChapterSource.CACHE }
            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, downloadedChapters = chaptersFromCache.size))

            Logger.logInfo("--- Starting Desktop Job: ${op.customTitle} (${op.jobId}) ---")
            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, "Starting...", 0.05f))

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
                    isPaused = { false }, // Pausing is handled via coroutine cancellation
                    dryRun = false,
                    onProgressUpdate = { chapterProgress, status ->
                        val update = JobStatusUpdate(op.jobId, status = status, progress = chapterProgress)
                        _jobStatusFlow.tryEmit(update)
                    },
                    onChapterCompleted = { completedChapterUrl ->
                        val update = JobStatusUpdate(op.jobId, downloadedChapters = 1)
                        _jobStatusFlow.tryEmit(update)

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

            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, status = "Packaging..."))
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
            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, status = "Completed", progress = 1f, isFinished = true))
            Logger.logInfo("--- Finished Desktop Job: ${op.customTitle} ---")

        } catch (e: CancellationException) {
            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, status = "Paused", isFinished = true))
            Logger.logInfo("Job ${op.jobId} was stopped by its manager.")
        } catch (e: ClientRequestException) {
            val errorMessage = "Paused (Server Error)"
            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, status = errorMessage, isFinished = false, errorMessage = e.message))
            Logger.logError("Job ${op.jobId} paused due to server error: ${e.response.status}", e)
        } catch (e: IOException) {
            val errorMessage = "Paused (Network Error)"
            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, status = errorMessage, isFinished = false, errorMessage = e.message))
            Logger.logError("Job ${op.jobId} paused due to network error", e)
        } catch (e: Exception) {
            val errorMessage = "Error: ${e.message?.take(40) ?: "Unknown"}"
            Logger.logError("Job ${op.jobId} failed", e)
            _jobStatusFlow.tryEmit(JobStatusUpdate(op.jobId, status = errorMessage, isFinished = true, errorMessage = e.message))
        }
    }
}
