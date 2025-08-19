package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.pointer.tooltipHoverFix

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun JobEditDialog(
    state: UiState,
    onEvent: (Event) -> Unit
) {
    val jobContext = state.editingJobContext ?: return
    val job = state.downloadQueue.find { it.id == jobContext.jobId }

    var title by remember(jobContext.customTitle) { mutableStateOf(jobContext.customTitle) }
    var outputPath by remember(jobContext.outputPath) { mutableStateOf(jobContext.outputPath) }
    var format by remember(jobContext.outputFormat) { mutableStateOf(jobContext.outputFormat) }
    var workers by remember(jobContext.workers) { mutableStateOf(jobContext.workers) }
    var formatDropdownExpanded by remember { mutableStateOf(false) }

    val isEditable = job?.status == "Queued" || job?.status == "Paused"

    val submitAction = {
        if (isEditable) {
            onEvent(Event.Queue.UpdateJob(jobContext.jobId, title, outputPath, format, workers))
        }
    }

    Dialog(onDismissRequest = { onEvent(Event.Queue.CancelEditJob) }) {
        Surface(
            modifier = Modifier.width(600.dp),
            shape = MaterialTheme.shapes.large,
            elevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Edit Job", style = MaterialTheme.typography.h5)

                SubmitTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Output Filename") },
                    onSubmit = submitAction,
                    options = SubmitTextFieldOptions(
                        enabled = isEditable,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                )

                SubmitTextField(
                    value = outputPath,
                    onValueChange = { outputPath = it },
                    label = { Text("Output Directory") },
                    onSubmit = submitAction,
                    options = SubmitTextFieldOptions(
                        enabled = isEditable,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                onClick = { onEvent(Event.Queue.PickJobOutputPath) },
                                enabled = isEditable
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Browse for output directory")
                            }
                        }
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Image Workers:", style = MaterialTheme.typography.body1)
                    NumberStepper(
                        value = workers,
                        onValueChange = { workers = it },
                        range = 1..16,
                        enabled = isEditable
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth().tooltipHoverFix()
                ) {
                    OutlinedButton(
                        onClick = { formatDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isEditable
                    ) {
                        Text("Format: ${format.uppercase()}")
                        Icon(Icons.Default.ArrowDropDown, "Format")
                    }
                    DropdownMenu(
                        expanded = formatDropdownExpanded,
                        onDismissRequest = { formatDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(onClick = {
                            format = "epub"
                            formatDropdownExpanded = false
                        }) { Text("EPUB") }
                    }
                }

                Button(
                    onClick = { onEvent(Event.Queue.RequestEditJobChapters(jobContext.jobId)) },
                    enabled = isEditable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Edit Chapters...")
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            onEvent(Event.Queue.CancelJob(jobContext.jobId))
                            onEvent(Event.Queue.CancelEditJob)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colors.error)
                    ) {
                        Text("Cancel Job")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { onEvent(Event.Queue.CancelEditJob) }) {
                        Text("Close")
                    }
                    Button(
                        onClick = submitAction,
                        enabled = isEditable
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        }
    }
}
