package com.mangacombiner.ui.widget

import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable

@Composable
fun UpdateDownloadedDialog(
    onDismissRequest: () -> Unit,
    onRestartClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Update Downloaded") },
        text = { Text("The update has been downloaded. Please restart the application to apply the update.") },
        confirmButton = {
            Button(onClick = onRestartClick) {
                Text("Restart")
            }
        },
        dismissButton = {
            Button(onClick = onDismissRequest) {
                Text("Later")
            }
        }
    )
}
