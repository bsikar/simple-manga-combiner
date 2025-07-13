package com.mangacombiner.service

import android.content.Context
import android.content.Intent
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.toSlug
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Android-specific implementation of the BackgroundDownloader interface.
 * This class acts as a proxy. It does not perform downloads itself. Instead, it
 * constructs and sends Intents to the real BackgroundDownloaderService.
 */
class AndroidBackgroundDownloader(
    private val context: Context,
    private val queuePersistenceService: QueuePersistenceService,
    private val platformProvider: PlatformProvider,
) : BackgroundDownloader {

    // It exposes the shared flow from the singleton holder.
    override val jobStatusFlow: SharedFlow<JobStatusUpdate> = JobStatusHolder.jobStatusFlow

    override fun startJob(op: QueuedOperation) {
        // Persist the operation's metadata so the service can retrieve it.
        queuePersistenceService.saveOperationMetadata(op)

        val seriesSlug = op.seriesUrl.toSlug()
        val seriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")

        val intent = Intent(context, BackgroundDownloaderService::class.java).apply {
            action = BackgroundDownloaderService.ACTION_START_JOB
            putExtra(BackgroundDownloaderService.EXTRA_JOB_ID, op.jobId)
            putExtra(BackgroundDownloaderService.EXTRA_SERIES_PATH, seriesDir.absolutePath)
        }
        context.startService(intent)
        Logger.logDebug { "Sent ACTION_START_JOB intent for ${op.jobId}" }
    }

    override fun stopJob(jobId: String) {
        val intent = Intent(context, BackgroundDownloaderService::class.java).apply {
            action = BackgroundDownloaderService.ACTION_STOP_JOB
            putExtra(BackgroundDownloaderService.EXTRA_JOB_ID, jobId)
        }
        context.startService(intent) // No need for foreground to just send a stop command
    }

    override fun stopAllJobs() {
        val intent = Intent(context, BackgroundDownloaderService::class.java).apply {
            action = BackgroundDownloaderService.ACTION_STOP_ALL
        }
        context.startService(intent)
    }
}
