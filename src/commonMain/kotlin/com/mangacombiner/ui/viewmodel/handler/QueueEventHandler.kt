package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.util.Logger
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun MainViewModel.handleQueueEvent(event: Event.Queue) {
    when (event) {
        is Event.Queue.Add -> addCurrentJobToQueue()
        is Event.Queue.ConfirmAddDuplicate -> state.value.jobContextToAdd?.let { addJobToQueueAndResetState(it) }
        is Event.Queue.CancelAddDuplicate -> _state.update {
            it.copy(
                showAddDuplicateDialog = false, jobContextToAdd = null
            )
        }
        is Event.Queue.ClearCompleted -> clearCompletedJobs()
        is Event.Queue.CancelJob -> cancelJob(event.jobId)
        is Event.Queue.RequestEditJob -> _state.update { it.copy(editingJobId = event.jobId, editingJobContext = getJobContext(event.jobId)) }
        is Event.Queue.RequestEditJobChapters -> onRequestEditJobChapters(event.jobId)
        is Event.Queue.CancelEditJob -> _state.update { it.copy(editingJobId = null, editingJobContext = null) }
        is Event.Queue.UpdateJob -> updateJob(event)
        is Event.Queue.PickJobOutputPath -> viewModelScope.launch {
            _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.JOB_OUTPUT))
        }
        is Event.Queue.TogglePauseJob -> togglePauseJob(event.jobId, event.force)
        is Event.Queue.MoveJob -> moveJob(event.jobId, event.direction)
        Event.Queue.PauseAll -> pauseAllJobs()
        Event.Queue.ResumeAll -> resumeAllJobs()
    }
}

private fun MainViewModel.onRequestEditJobChapters(jobId: String) {
    val op = getJobContext(jobId) ?: return
    _state.update {
        it.copy(
            fetchedChapters = op.chapters,
            showChapterDialog = true,
            editingJobId = null, // Close the small edit dialog
            editingJobContext = null,
            editingJobIdForChapters = jobId // Set the new flag
        )
    }
}

private fun MainViewModel.pauseAllJobs() {
    _state.update { currentState ->
        val updatedQueue = currentState.downloadQueue.map { job ->
            val isFinished =
                job.status == "Completed" || job.status.startsWith("Error") || job.status == "Cancelled"
            if (!isFinished && !job.isIndividuallyPaused) {
                job.copy(status = "Paused")
            } else {
                job
            }
        }
        currentState.copy(
            isQueueGloballyPaused = true,
            downloadQueue = updatedQueue,
        )
    }
}

private fun MainViewModel.resumeAllJobs() {
    _state.update { currentState ->
        val updatedQueue = currentState.downloadQueue.map { job ->
            val isFinished = job.status == "Completed" || job.status.startsWith("Error") || job.status == "Cancelled"
            if (!isFinished) {
                job.copy(status = "Queued", isIndividuallyPaused = false)
            } else {
                job
            }
        }
        currentState.copy(isQueueGloballyPaused = false, downloadQueue = updatedQueue)
    }
}


private fun MainViewModel.togglePauseJob(jobId: String, force: Boolean? = null) {
    _state.update { currentState ->
        val jobToUpdate = currentState.downloadQueue.find { it.id == jobId } ?: return@update currentState

        val newPausedState = force ?: !jobToUpdate.isIndividuallyPaused

        if (newPausedState) {
            // We are PAUSING the job, so tell the service to stop it.
            backgroundDownloader.stopJob(jobId)
            activeServiceJobs.remove(jobId)
        }
        // When RESUMING (newPausedState is false), we just change the state to "Queued".
        // The reactive queue processor will see the change and start the job if it's eligible.

        currentState.copy(
            downloadQueue = currentState.downloadQueue.map { job ->
                if (job.id == jobId) {
                    val isFinished = job.status == "Completed" || job.status.startsWith("Error") || job.status == "Cancelled"
                    if (isFinished) {
                        job // Don't change finished jobs
                    } else {
                        job.copy(
                            isIndividuallyPaused = newPausedState,
                            status = if (newPausedState) "Paused" else "Queued"
                        )
                    }
                } else {
                    job
                }
            }
        )
    }
}

private fun MainViewModel.moveJob(jobId: String, direction: Event.Queue.MoveDirection) {
    _state.update { currentState ->
        val queue = currentState.downloadQueue.toMutableList()
        val index = queue.indexOfFirst { it.id == jobId }
        if (index == -1) return@update currentState // Job not found

        val newIndex = if (direction == Event.Queue.MoveDirection.UP) index - 1 else index + 1

        if (newIndex in queue.indices) {
            val item = queue.removeAt(index)
            queue.add(newIndex, item)
        }
        currentState.copy(downloadQueue = queue)
    }
}
