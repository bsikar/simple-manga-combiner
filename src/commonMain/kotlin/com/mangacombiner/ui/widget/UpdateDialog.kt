package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import com.mangacombiner.model.GithubRelease

@Composable
fun UpdateDialog(
    latestRelease: GithubRelease,
    onDismissRequest: () -> Unit,
    onUpdateClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Update Available") },
        text = {
            Column {
                Text("A new version of the app is available.")
                Text("Latest version: ${latestRelease.tagName}")
            }
        },
        confirmButton = {
            Button(onClick = onUpdateClick) {
                Text("Update")
            }
        },
        dismissButton = {
            Button(onClick = onDismissRequest) {
                Text("Later")
            }
        }
    )
}
