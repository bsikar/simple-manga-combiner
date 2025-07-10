package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
        // --- Header and Global Controls ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Download Queue", style = MaterialTheme.typography.h5, modifier = Modifier.weight(1f))
            TextButton(onClick = { onEvent(Event.Queue.ClearCompleted) }) {
                Text("Clear Completed")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showAdvancedSettings = !showAdvancedSettings },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Advanced Settings")
            Icon(
                if (showAdvancedSettings) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Toggle Advanced Settings"
            )
        }

        // --- Advanced Settings Panel ---
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
                        onClick = { onEvent(Event.Settings.TogglePerWorkerUserAgent(!state.perWorkerUserAgent)) },
                        enabled = true,
                        control = {
                            Switch(
                                checked = state.perWorkerUserAgent,
                                onCheckedChange = { onEvent(Event.Settings.TogglePerWorkerUserAgent(it)) }
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

        // --- Overall Progress ---
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

        // --- Queue List ---
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
                itemsIndexed(state.downloadQueue, key = { _, item -> item.id }) { index, job ->
                    DownloadJobItem(
                        job = job,
                        onEvent = onEvent,
                        isFirst = index == 0,
                        isLast = index == state.downloadQueue.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadJobItem(job: DownloadJob, onEvent: (Event) -> Unit, isFirst: Boolean, isLast: Boolean) {
    val animatedProgress by animateFloatAsState(
        targetValue = job.progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
    )
    val isFinished = job.status == "Completed" || job.status.startsWith("Error") || job.status == "Cancelled"
    val isRunning = job.status == "Downloading" || job.status == "Pausing..." || job.status == "Packaging..."

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reorder Controls
                Column {
                    IconButton(
                        onClick = { onEvent(Event.Queue.MoveJob(job.id, Event.Queue.MoveDirection.UP)) },
                        enabled = !isFirst
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move Up")
                    }
                    IconButton(
                        onClick = { onEvent(Event.Queue.MoveJob(job.id, Event.Queue.MoveDirection.DOWN)) },
                        enabled = !isLast
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move Down")
                    }
                }

                // Job Info
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp).clickable {
                    if (!isFinished) onEvent(Event.Queue.RequestEditJob(job.id))
                }) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.h6,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${job.downloadedChapters} / ${job.totalChapters} Chapters",
                        style = MaterialTheme.typography.body2
                    )
                    Spacer(Modifier.height(8.dp))
                    if (job.progress < 1f && job.status != "Queued" && !isFinished) {
                        LinearProgressIndicator(
                            progress = animatedProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Spacer to keep height consistent
                        Spacer(Modifier.height(4.dp))
                    }
                }

                // Action Controls
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = job.status,
                        style = MaterialTheme.typography.caption,
                        color = when (job.status) {
                            "Downloading" -> MaterialTheme.colors.primary
                            "Paused", "Pausing..." -> MaterialTheme.colors.secondary
                            else -> LocalContentColor.current.copy(alpha = 0.7f)
                        }
                    )
                    Row {
                        if (!isFinished) {
                            IconButton(onClick = { onEvent(Event.Queue.TogglePauseJob(job.id)) }) {
                                Icon(
                                    if (job.isIndividuallyPaused || job.status == "Paused") Icons.Default.PlayArrow else Icons.Default.Pause,
                                    if (job.isIndividuallyPaused || job.status == "Paused") "Resume Job" else "Pause Job"
                                )
                            }
                            IconButton(onClick = { onEvent(Event.Queue.CancelJob(job.id)) }) {
                                Icon(Icons.Default.Cancel, "Cancel Job")
                            }
                        } else {
                            // Placeholder to maintain layout
                            Spacer(Modifier.width(96.dp))
                        }
                    }
                }
            }
        }
    }
}
