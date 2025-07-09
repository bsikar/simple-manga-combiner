package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.util.logOperationSettings
import kotlinx.coroutines.flow.update

internal fun MainViewModel.handleOperationEvent(event: Event.Operation) {
    when (event) {
        Event.Operation.RequestStart -> onRequestStartOperation()
        Event.Operation.ConfirmOverwrite -> onConfirmOverwrite()
        Event.Operation.CancelOverwrite -> _state.update { it.copy(showOverwriteConfirmationDialog = false) }
        Event.Operation.Pause -> _operationState.value = OperationState.PAUSED
        Event.Operation.Resume -> onResumeOperation()
        Event.Operation.RequestCancel -> _state.update { it.copy(showCancelDialog = true) }
        Event.Operation.ConfirmCancel -> onConfirmCancelOperation()
        Event.Operation.AbortCancel -> _state.update { it.copy(showCancelDialog = false) }
        is Event.Operation.ToggleDeleteCacheOnCancel -> _state.update { it.copy(deleteCacheOnCancel = event.delete) }
    }
}

private fun MainViewModel.onRequestStartOperation() {
    val s = _state.value
    if (s.sourceFilePath != null) {
        _state.update { it.copy(showOverwriteConfirmationDialog = true) }
    } else {
        startOperation()
    }
}

private fun MainViewModel.onConfirmOverwrite() {
    _state.update { it.copy(showOverwriteConfirmationDialog = false) }
    startOperation()
}

private fun MainViewModel.onResumeOperation() {
    _state.value.activeDownloadOptions?.let {
        logOperationSettings(
            it,
            it.chaptersToDownload.size,
            _state.value.userAgentName,
            _state.value.perWorkerUserAgent,
            isResuming = true
        )
    }
    _operationState.value = OperationState.RUNNING
}

private fun MainViewModel.onConfirmCancelOperation() {
    _state.update { it.copy(showCancelDialog = false) }
    _operationState.value = OperationState.CANCELLING
}
