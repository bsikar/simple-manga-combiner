package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.pointer.tooltipHoverFix
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DownloadQueueScreen(state: UiState, onEvent: (Event) -> Unit) {
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var browserDropdownExpanded by remember { mutableStateOf(false) }
    val browserImpersonationOptions = listOf("Random") + UserAgent.browsers.keys.toList()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Download Queue", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))

            val resumableJobs = state.downloadQueue.filter {
                it.status !in listOf("Completed", "Cancelled") && !it.status.startsWith("Error")
            }
            val canPause = resumableJobs.any { !it.isIndividuallyPaused && it.status != "Paused" }
            val canResume = state.isQueueGloballyPaused || (resumableJobs.isNotEmpty() && resumableJobs.all { it.isIndividuallyPaused || it.status == "Paused" })

            if (canResume) {
                TextButton(onClick = { onEvent(Event.Queue.ResumeAll) }) {
                    Text("Resume All")
                }
            } else if (canPause) {
                TextButton(onClick = { onEvent(Event.Queue.PauseAll) }) {
                    Text("Pause All")
                }
            }

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
                        Box(
                            modifier = Modifier.tooltipHoverFix()
                        ) {
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
                itemsIndexed(state.downloadQueue, key = { _, item -> item.id }) { index, job ->
                    DownloadJobItem(
                        job = job,
                        index = index,
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
private fun DownloadJobItem(job: DownloadJob, index: Int, onEvent: (Event) -> Unit, isFirst: Boolean, isLast: Boolean) {
    val animatedProgress by animateFloatAsState(
        targetValue = job.progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
    )
    val isFinished = job.status == "Completed" || job.status.startsWith("Error", true) || job.status == "Cancelled"
    val isRunning = !isFinished && (
            job.status.startsWith("Downloading", true) ||
                    job.status.startsWith("Starting", true) ||
                    job.status.startsWith("Packaging", true))

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                IconButton(
                    onClick = { onEvent(Event.Queue.MoveJob(job.id, Event.Queue.MoveDirection.UP)) },
                    enabled = !isFirst
                ) { Icon(Icons.Default.KeyboardArrowUp, "Move Up") }
                IconButton(
                    onClick = { onEvent(Event.Queue.MoveJob(job.id, Event.Queue.MoveDirection.DOWN)) },
                    enabled = !isLast
                ) { Icon(Icons.Default.KeyboardArrowDown, "Move Down") }
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isFinished) {
                        PlatformTooltip("Edit Job") {
                            IconButton(
                                onClick = { onEvent(Event.Queue.RequestEditJob(job.id)) },
                                modifier = Modifier.size(36.dp)
                            ) { Icon(Icons.Default.Edit, "Edit Job") }
                        }
                    }
                    PlatformTooltip(if (job.isIndividuallyPaused || job.status == "Paused") "Resume Job" else "Pause Job") {
                        IconButton(
                            onClick = { onEvent(Event.Queue.TogglePauseJob(job.id)) },
                            enabled = !isFinished,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (job.isIndividuallyPaused || job.status == "Paused") Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (job.isIndividuallyPaused || job.status == "Paused") "Resume Job" else "Pause Job"
                            )
                        }
                    }
                    PlatformTooltip(if (isFinished) "Remove From List" else "Cancel Job") {
                        IconButton(
                            onClick = { onEvent(Event.Queue.CancelJob(job.id)) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (isFinished) Icons.Default.Delete else Icons.Default.Cancel,
                                contentDescription = if (isFinished) "Remove Job" else "Cancel Job"
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.h6,
                    color = MaterialTheme.colors.primary.copy(alpha = 0.7f)
                )
                Text(
                    text = FileUtils.sanitizeFilename(job.title),
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(progress = animatedProgress, modifier = Modifier.weight(1f))
                Text(
                    text = "${job.downloadedChapters}/${job.totalChapters}",
                    style = MaterialTheme.typography.caption
                )
            }

            Text(
                text = if (isRunning) job.status else job.status.replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    isRunning -> MaterialTheme.colors.primary
                    job.status == "Paused" -> MaterialTheme.colors.secondary
                    else -> LocalContentColor.current.copy(alpha = 0.7f)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}
