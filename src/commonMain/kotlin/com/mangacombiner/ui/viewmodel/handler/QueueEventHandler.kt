package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.update

internal fun MainViewModel.handleQueueEvent(event: Event.Queue) {
    when (event) {
        is Event.Queue.Add -> addCurrentJobToQueue()
        is Event.Queue.PauseAll -> _state.update { it.copy(isQueuePaused = true) }
        is Event.Queue.ResumeAll -> _state.update { it.copy(isQueuePaused = false) }
        is Event.Queue.ClearCompleted -> clearCompletedJobs()
        is Event.Queue.CancelJob -> cancelJob(event.jobId)
    }
}
