package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.model.SearchResult
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.ui.viewmodel.state.SearchSortOption
import com.mangacombiner.ui.viewmodel.state.UiState

@Composable
fun DownloadScreen(state: UiState, onEvent: (Event) -> Unit) {
    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        val isIdle = state.operationState == OperationState.IDLE
        val isRunning = state.operationState == OperationState.RUNNING || state.operationState == OperationState.PAUSED

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(elevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Search for a Series", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { onEvent(Event.Search.UpdateQuery(it)) },
                        label = { Text("Search on MangaRead.org") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = isIdle,
                        trailingIcon = {
                            if (state.searchQuery.isNotBlank()) {
                                IconButton(
                                    onClick = { onEvent(Event.Search.UpdateQuery("")) },
                                    enabled = isIdle
                                ) {
                                    Icon(Icons.Filled.Clear, "Clear Search")
                                }
                            }
                        }
                    )
                    Button(
                        onClick = { onEvent(Event.Search.Perform) },
                        enabled = state.searchQuery.isNotBlank() && !state.isSearching && isIdle,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (state.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Searching...")
                        } else {
                            Text("Search")
                        }
                    }

                    AnimatedVisibility(visible = state.searchResults.isNotEmpty() || state.isSearching) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Search Results:", style = MaterialTheme.typography.subtitle1)
                                Box {
                                    OutlinedButton(
                                        onClick = { sortDropdownExpanded = true },
                                        enabled = !state.isSearching
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Sort, "Sort Results", modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Sort By")
                                    }
                                    DropdownMenu(
                                        expanded = sortDropdownExpanded,
                                        onDismissRequest = { sortDropdownExpanded = false }
                                    ) {
                                        DropdownMenuItem(onClick = {
                                            onEvent(Event.Search.SortResults(SearchSortOption.DEFAULT))
                                            sortDropdownExpanded = false
                                        }) {
                                            if (state.searchSortOption == SearchSortOption.DEFAULT) Icon(Icons.Default.Check, "Selected") else Spacer(Modifier.width(24.dp))
                                            Text("Default")
                                        }
                                        DropdownMenuItem(onClick = {
                                            onEvent(Event.Search.SortResults(SearchSortOption.CHAPTER_COUNT))
                                            sortDropdownExpanded = false
                                        }) {
                                            if (state.searchSortOption == SearchSortOption.CHAPTER_COUNT) Icon(Icons.Default.Check, "Selected") else Spacer(Modifier.width(24.dp))
                                            Text("Chapter Count")
                                        }
                                        DropdownMenuItem(onClick = {
                                            onEvent(Event.Search.SortResults(SearchSortOption.ALPHABETICAL))
                                            sortDropdownExpanded = false
                                        }) {
                                            if (state.searchSortOption == SearchSortOption.ALPHABETICAL) Icon(Icons.Default.Check, "Selected") else Spacer(Modifier.width(24.dp))
                                            Text("Alphabetical")
                                        }
                                    }
                                }
                            }
                            Divider()
                            state.searchResults.forEach { result ->
                                SearchResultItem(
                                    result = result,
                                    onExpandToggle = { onEvent(Event.Search.ToggleResultExpansion(result.url)) },
                                    onSelect = { onEvent(Event.Search.SelectResult(result.url)) }
                                )
                                Divider()
                            }
                        }
                    }
                }
            }

            Card(elevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download & Sync Options", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.seriesUrl,
                        onValueChange = { onEvent(Event.Download.UpdateUrl(it)) },
                        label = { Text("Series URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = isIdle,
                        trailingIcon = {
                            if (state.seriesUrl.isNotBlank()) {
                                IconButton(
                                    onClick = { onEvent(Event.Download.ClearInputs) },
                                    enabled = isIdle
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Clear URL and Filename"
                                    )
                                }
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onEvent(Event.Download.PickLocalFile) },
                            enabled = isIdle
                        ) {
                            Text("Update Local File...")
                        }

                        Button(
                            onClick = { onEvent(Event.Download.FetchChapters) },
                            enabled = state.seriesUrl.isNotBlank() && !state.isFetchingChapters && isIdle,
                        ) {
                            if (state.isFetchingChapters) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Fetching...")
                            } else {
                                Text("Fetch Chapters")
                            }
                        }
                    }

                    if (state.isAnalyzingFile) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Analyzing file...")
                        }
                    }

                    Divider()

                    OutlinedTextField(
                        value = state.customTitle,
                        onValueChange = { onEvent(Event.Download.UpdateCustomTitle(it)) },
                        label = { Text("Output Filename (without extension)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle
                    )

                    OutlinedTextField(
                        value = state.outputPath,
                        onValueChange = { onEvent(Event.Download.UpdateOutputPath(it)) },
                        label = { Text("Output Directory") },
                        placeholder = { Text("Default: Your Downloads folder") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle,
                        trailingIcon = {
                            IconButton(
                                onClick = { onEvent(Event.Download.PickOutputPath) },
                                enabled = isIdle
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Browse for output directory")
                            }
                        }
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Workers:", style = MaterialTheme.typography.body1)
                            NumberStepper(
                                value = state.workers,
                                onValueChange = { onEvent(Event.Settings.UpdateWorkers(it)) },
                                range = 1..16,
                                enabled = isIdle
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { formatDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isIdle
                            ) {
                                Text("Format: ${state.outputFormat.uppercase()}")
                                Icon(Icons.Default.ArrowDropDown, "Format")
                            }
                            DropdownMenu(
                                expanded = formatDropdownExpanded,
                                onDismissRequest = { formatDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(onClick = {
                                    onEvent(Event.Download.UpdateFormat("cbz"))
                                    formatDropdownExpanded = false
                                }) { Text("CBZ") }
                                DropdownMenuItem(onClick = {
                                    onEvent(Event.Download.UpdateFormat("epub"))
                                    formatDropdownExpanded = false
                                }) { Text("EPUB") }
                            }
                        }
                    }
                }
            }
        }

        if (isRunning && state.activeDownloadOptions != null) {
            val animatedProgress by animateFloatAsState(
                targetValue = state.progress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Text(
                    text = state.progressStatusText,
                    style = MaterialTheme.typography.caption
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.operationState) {
                OperationState.IDLE -> {
                    val buttonText = if (state.sourceFilePath != null) "Sync & Update File" else "Add to Queue"
                    Button(
                        onClick = {
                            if (state.sourceFilePath != null) {
                                onEvent(Event.Operation.RequestStart)
                            } else {
                                onEvent(Event.Queue.Add)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.fetchedChapters.any { it.selectedSource != null }
                    ) {
                        Text(buttonText)
                    }
                }
                OperationState.RUNNING -> {
                    Button(
                        onClick = { onEvent(Event.Operation.Pause) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pause")
                    }
                    Button(
                        onClick = { onEvent(Event.Operation.RequestCancel) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                OperationState.PAUSED -> {
                    Button(
                        onClick = { onEvent(Event.Operation.Resume) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Resume")
                    }
                    Button(
                        onClick = { onEvent(Event.Operation.RequestCancel) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                OperationState.CANCELLING -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelling...")
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    }
                }
            }
        }
    }
}
