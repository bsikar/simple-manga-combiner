package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState

@Composable
fun BrokenDownloadDialog(state: UiState, onEvent: (Event) -> Unit) {
    val failedCount = state.lastDownloadResult?.failedChapters?.size ?: 0
    if (failedCount == 0) return

    AlertDialog(
        onDismissRequest = { onEvent(Event.Operation.DiscardFailed) },
        title = { Text("Download Incomplete") },
        text = {
            Column {
                Text(
                    "The download completed, but $failedCount chapter(s) had missing images. " +
                            "You can create a partial file now, retry only the failed chapters, or discard the result."
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Failed Chapters:",
                    style = MaterialTheme.typography.subtitle2
                )
                state.lastDownloadResult?.failedChapters?.keys?.forEach {
                    Text("- $it")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(Event.Operation.ConfirmBrokenDownload) }) {
                Text("Create Partial File")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onEvent(Event.Operation.DiscardFailed) }) {
                    Text("Discard")
                }
                TextButton(
                    onClick = { onEvent(Event.Operation.RetryFailed) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colors.secondary)
                ) {
                    Text("Retry Failed")
                }
            }
        }
    )
}
