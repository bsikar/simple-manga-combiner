package com.mangacombiner.ui.widget

import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState

@Composable
fun CompletionDialog(state: UiState, onEvent: (Event) -> Unit) {
    val result = state.lastDownloadResult
    val title = if (result?.failedChapters?.isNotEmpty() == true) "Partial Download Complete" else "Download Complete"
    val message = state.completionMessage ?: if (result?.failedChapters?.isNotEmpty() == true) {
        "A partial file has been created with ${result.successfulFolders.size} successful chapters. " +
                "It contains metadata about the ${result.failedChapters.size} failed chapters so you can fix it later using the 'Update Local File' feature."
    } else {
        "Successfully downloaded and packaged ${result?.successfulFolders?.size ?: 0} chapters."
    }

    AlertDialog(
        onDismissRequest = { onEvent(Event.Operation.DiscardFailed) }, // Discard also dismisses
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onEvent(Event.Operation.DiscardFailed) }) {
                Text("OK")
            }
        }
    )
}
