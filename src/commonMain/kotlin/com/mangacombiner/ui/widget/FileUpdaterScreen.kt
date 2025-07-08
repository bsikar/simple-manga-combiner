package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.ChapterInFile
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.ui.viewmodel.UiState

@Composable
fun FileUpdaterScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    val isIdle = state.operationState == OperationState.IDLE
    val isProcessing = !isIdle

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Local File Updater", style = MaterialTheme.typography.h5)
        Text(
            "Select a CBZ or EPUB file to modify its chapters. Uncheck any chapters you wish to remove.",
            style = MaterialTheme.typography.body2
        )
        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { onEvent(MainViewModel.Event.PickLocalFile) },
                    enabled = isIdle
                ) {
                    Text("Select EPUB or CBZ file")
                }

                if (state.localFilePath.isNotBlank()) {
                    Text(
                        text = "Selected file: ${state.localFilePath}",
                        style = MaterialTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.isAnalyzingFile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Analyzing file...")
                    }
                }
            }
        }

        if (state.localFileChapters.isNotEmpty()) {
            Column(modifier = Modifier.weight(1f)) {
                val selectedCount = state.localFileChapters.count { it.isSelected }
                Text("Chapters found (${state.localFileChapters.size}) - Keeping $selectedCount")
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxSize(), elevation = 2.dp) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(state.localFileChapters, key = { it.name }) { chapter ->
                            ChapterItem(chapter, onEvent, enabled = isIdle)
                        }
                    }
                }
            }

            Button(
                onClick = { onEvent(MainViewModel.Event.StartLocalFileUpdate) },
                enabled = isIdle && state.localFileChapters.any { it.isSelected },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Update File")
            }
        } else if (isProcessing) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Text(
                    text = state.progressStatusText,
                    style = MaterialTheme.typography.caption
                )
            }
        }
    }
}

@Composable
private fun ChapterItem(chapter: ChapterInFile, onEvent: (MainViewModel.Event) -> Unit, enabled: Boolean) {
    FormControlLabel(
        onClick = { onEvent(MainViewModel.Event.ToggleLocalFileChapterSelection(chapter.name, !chapter.isSelected)) },
        enabled = enabled,
        control = {
            Checkbox(
                checked = chapter.isSelected,
                onCheckedChange = { onEvent(MainViewModel.Event.ToggleLocalFileChapterSelection(chapter.name, it)) },
                enabled = enabled
            )
        },
        label = {
            Text(text = chapter.name.replace(Regex("[_]"), " ").replaceFirstChar { it.titlecase() })
        }
    )
}
