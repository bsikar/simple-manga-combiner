package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.update

internal fun MainViewModel.handleLogEvent(event: Event.Log) {
    when (event) {
        Event.Log.Clear -> _logs.value = listOf("Logs cleared.")
        Event.Log.CopyToClipboard -> clipboardManager.copyToClipboard(_logs.value.joinToString("\n"))
        Event.Log.ToggleAutoscroll -> _state.update { it.copy(logAutoscrollEnabled = !it.logAutoscrollEnabled) }
    }
}
