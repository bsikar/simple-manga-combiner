package com.mangacombiner.service

import com.mangacombiner.model.QueuedOperation
import kotlinx.coroutines.flow.SharedFlow

/**
 * A data class to emit status updates from the downloader to the ViewModel.
 */
data class JobStatusUpdate(
    val jobId: String,
    val status: String? = null,
    val progress: Float? = null,
    val downloadedChapters: Int? = null,
    val isFinished: Boolean = false,
    val errorMessage: String? = null
)

/**
 * A platform-agnostic interface for starting and stopping background download jobs.
 * This allows the common ViewModel to interact with platform-specific implementations
 * (a real Service on Android, a direct coroutine on Desktop).
 */
interface BackgroundDownloader {
    /**
     * A flow that emits status updates for running jobs.
     */
    val jobStatusFlow: SharedFlow<JobStatusUpdate>

    /**
     * Starts processing a job.
     */
    fun startJob(op: QueuedOperation)

    /**
     * Requests to stop a specific job.
     */
    fun stopJob(jobId: String)

    /**
     * Requests to stop all currently running jobs.
     */
    fun stopAllJobs()
}
