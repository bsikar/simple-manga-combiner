package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun MainViewModel.handleQueueEvent(event: Event.Queue) {
    when (event) {
        is Event.Queue.Add -> addCurrentJobToQueue()
        is Event.Queue.ClearCompleted -> clearCompletedJobs()
        is Event.Queue.CancelJob -> cancelJob(event.jobId)
        is Event.Queue.RequestEditJob -> _state.update { it.copy(editingJobId = event.jobId, editingJobContext = getJobContext(event.jobId)) }
        is Event.Queue.CancelEditJob -> _state.update { it.copy(editingJobId = null, editingJobContext = null) }
        is Event.Queue.UpdateJob -> updateJob(event)
        is Event.Queue.PickJobOutputPath -> viewModelScope.launch {
            _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.JOB_OUTPUT))
        }
        is Event.Queue.TogglePauseJob -> togglePauseJob(event.jobId)
        is Event.Queue.MoveJob -> moveJob(event.jobId, event.direction)
        Event.Queue.PauseAll -> pauseAllJobs()
        Event.Queue.ResumeAll -> resumeAllJobs()
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
            if (job.status == "Paused" && !job.isIndividuallyPaused) {
                job.copy(status = "Queued")
            } else {
                job
            }
        }
        currentState.copy(isQueueGloballyPaused = false, downloadQueue = updatedQueue)
    }
}


private fun MainViewModel.togglePauseJob(jobId: String) {
    _state.update { currentState ->
        currentState.copy(
            downloadQueue = currentState.downloadQueue.map { job ->
                if (job.id == jobId) {
                    val newPausedState = !job.isIndividuallyPaused
                    val newStatus: String
                    if (newPausedState) {
                        // If we are pausing it, always set status to "Paused" unless it's already finished.
                        val isFinished = job.status == "Completed" || job.status.startsWith("Error") || job.status == "Cancelled"
                        newStatus = if (isFinished) job.status else "Paused"
                    } else {
                        // If we are resuming, set it back to "Queued". The collector will start it if it has priority.
                        newStatus = "Queued"
                    }
                    job.copy(isIndividuallyPaused = newPausedState, status = newStatus)
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
