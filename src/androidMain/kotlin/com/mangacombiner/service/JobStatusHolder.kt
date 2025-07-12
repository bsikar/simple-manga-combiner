package com.mangacombiner.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A simple singleton to hold a shared flow for job status updates.
 * This decouples the ViewModel from the Service instance, allowing them
 * to communicate through this central, shared flow.
 */
object JobStatusHolder {
    private val _jobStatusFlow = MutableSharedFlow<JobStatusUpdate>(extraBufferCapacity = 128)
    val jobStatusFlow = _jobStatusFlow.asSharedFlow()

    fun postUpdate(update: JobStatusUpdate) {
        _jobStatusFlow.tryEmit(update)
    }
}
