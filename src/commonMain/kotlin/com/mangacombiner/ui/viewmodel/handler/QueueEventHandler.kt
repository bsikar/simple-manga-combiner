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
    }
}

private fun MainViewModel.togglePauseJob(jobId: String) {
    _state.update { currentState ->
        currentState.copy(
            downloadQueue = currentState.downloadQueue.map { job ->
                if (job.id == jobId) {
                    val newPausedState = !job.isIndividuallyPaused
                    // Let the running job itself update its status to "Paused".
                    // If not running, we can toggle between "Queued" and "Paused".
                    val newStatus = if (job.status == "Queued" || job.status == "Paused") {
                        if (newPausedState) "Paused" else "Queued"
                    } else {
                        job.status // Don't change status of a running job here
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
