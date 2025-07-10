package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.UserAgent
import kotlin.math.roundToInt

@Composable
fun DownloadQueueScreen(state: UiState, onEvent: (Event) -> Unit) {
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var browserDropdownExpanded by remember { mutableStateOf(false) }
    val browserImpersonationOptions = listOf("Random") + UserAgent.browsers.keys.toList()

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Download Queue", style = MaterialTheme.typography.h5)
        Spacer(Modifier.height(8.dp))

        // Control buttons for the queue
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showAdvancedSettings = !showAdvancedSettings },
                modifier = Modifier.weight(1f)
            ) {
                Text("Advanced Settings")
                Icon(
                    if (showAdvancedSettings) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle Advanced Settings"
                )
            }
            TextButton(
                onClick = { onEvent(Event.Queue.ClearCompleted) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear Completed")
            }

            if (state.isQueuePaused) {
                Button(
                    onClick = { onEvent(Event.Queue.ResumeAll) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Resume All")
                }
            } else {
                OutlinedButton(
                    onClick = { onEvent(Event.Queue.PauseAll) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pause All")
                }
            }
        }

        // Animated visibility for Advanced Settings
        AnimatedVisibility(visible = showAdvancedSettings) {
            Card(
                elevation = 4.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Settings for New Queue Items", style = MaterialTheme.typography.h6)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Concurrent Series Downloads:",
                            style = MaterialTheme.typography.body1,
                            modifier = Modifier.weight(1f)
                        )
                        NumberStepper(
                            value = state.batchWorkers,
                            onValueChange = { onEvent(Event.Settings.UpdateBatchWorkers(it)) },
                            range = 1..8,
                            enabled = true
                        )
                    }

                    Divider()

                    FormControlLabel(
                        onClick = { onEvent(Event.Download.ToggleDryRun(!state.dryRun)) },
                        enabled = true,
                        control = {
                            Switch(
                                checked = state.dryRun,
                                onCheckedChange = { onEvent(Event.Download.ToggleDryRun(it)) },
                                enabled = true
                            )
                        },
                        label = { Text("Dry Run (Simulate Only)") }
                    )

                    FormControlLabel(
                        onClick = { onEvent(Event.Settings.TogglePerWorkerUserAgent(!state.perWorkerUserAgent)) },
                        enabled = true,
                        control = {
                            Switch(
                                checked = state.perWorkerUserAgent,
                                onCheckedChange = { onEvent(Event.Settings.TogglePerWorkerUserAgent(it)) },
                                enabled = true
                            )
                        },
                        label = { Text("Randomize browser per worker") }
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Impersonate Browser:",
                            style = MaterialTheme.typography.body1,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            OutlinedButton(
                                onClick = { browserDropdownExpanded = true },
                                enabled = !state.perWorkerUserAgent
                            ) {
                                Text(state.userAgentName)
                                Icon(Icons.Default.ArrowDropDown, "Impersonate Browser")
                            }
                            DropdownMenu(
                                expanded = browserDropdownExpanded,
                                onDismissRequest = { browserDropdownExpanded = false },
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        browserImpersonationOptions.forEach { name ->
                                            DropdownMenuItem(onClick = {
                                                onEvent(Event.Settings.UpdateUserAgent(name))
                                                browserDropdownExpanded = false
                                            }) { Text(name) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (state.downloadQueue.isNotEmpty()) {
            val overallProgress by animateFloatAsState(
                targetValue = state.overallQueueProgress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = overallProgress,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(overallProgress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.caption
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (state.downloadQueue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("The download queue is empty.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.downloadQueue, key = { it.id }) { job ->
                    DownloadJobItem(job, onEvent)
                }
            }
        }
    }
}

@Composable
private fun DownloadJobItem(job: DownloadJob, onEvent: (Event) -> Unit) {
    val animatedProgress by animateFloatAsState(
        targetValue = job.progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
    )
    val isDone = job.status == "Completed" || job.status.startsWith("Error")
    val cardColor = if (isDone) {
        MaterialTheme.colors.surface.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colors.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onEvent(Event.Queue.RequestEditJob(job.id)) },
        elevation = 2.dp,
        backgroundColor = cardColor
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.h6,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!isDone) {
                    IconButton(onClick = { onEvent(Event.Queue.CancelJob(job.id)) }) {
                        Icon(Icons.Default.Cancel, "Cancel Job")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${job.downloadedChapters} / ${job.totalChapters} Chapters",
                    style = MaterialTheme.typography.body2
                )
                Text(
                    text = job.status,
                    style = MaterialTheme.typography.body2,
                    color = when (job.status) {
                        "Downloading" -> MaterialTheme.colors.primary
                        "Paused" -> MaterialTheme.colors.secondary
                        "Completed" -> MaterialTheme.colors.primary.copy(alpha = 0.7f)
                        else -> LocalContentColor.current
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            if (job.progress < 1f && job.status != "Queued") {
                LinearProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
