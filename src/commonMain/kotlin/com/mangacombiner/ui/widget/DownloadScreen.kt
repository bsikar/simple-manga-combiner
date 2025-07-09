package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.model.SearchResult
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.ui.viewmodel.SearchSortOption
import com.mangacombiner.ui.viewmodel.UiState

@Composable
fun DownloadScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        val isIdle = state.operationState == OperationState.IDLE
        val isRunning = state.operationState == OperationState.RUNNING || state.operationState == OperationState.PAUSED
        val isProcessing = state.operationState != OperationState.CANCELLING

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
                        onValueChange = { onEvent(MainViewModel.Event.UpdateSearchQuery(it)) },
                        label = { Text("Search on MangaRead.org") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = isIdle,
                        trailingIcon = {
                            if (state.searchQuery.isNotBlank()) {
                                IconButton(
                                    onClick = { onEvent(MainViewModel.Event.UpdateSearchQuery("")) },
                                    enabled = isIdle
                                ) {
                                    Icon(Icons.Filled.Clear, "Clear Search")
                                }
                            }
                        }
                    )
                    Button(
                        onClick = { onEvent(MainViewModel.Event.PerformSearch) },
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
                                            onEvent(MainViewModel.Event.SortSearchResults(SearchSortOption.DEFAULT))
                                            sortDropdownExpanded = false
                                        }) {
                                            if (state.searchSortOption == SearchSortOption.DEFAULT) Icon(Icons.Default.Check, "Selected") else Spacer(Modifier.width(24.dp))
                                            Text("Default")
                                        }
                                        DropdownMenuItem(onClick = {
                                            onEvent(MainViewModel.Event.SortSearchResults(SearchSortOption.CHAPTER_COUNT))
                                            sortDropdownExpanded = false
                                        }) {
                                            if (state.searchSortOption == SearchSortOption.CHAPTER_COUNT) Icon(Icons.Default.Check, "Selected") else Spacer(Modifier.width(24.dp))
                                            Text("Chapter Count")
                                        }
                                        DropdownMenuItem(onClick = {
                                            onEvent(MainViewModel.Event.SortSearchResults(SearchSortOption.ALPHABETICAL))
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
                                    onExpandToggle = { onEvent(MainViewModel.Event.ToggleSearchResultExpansion(result.url)) },
                                    onSelect = { onEvent(MainViewModel.Event.SelectSearchResult(result.url)) }
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
                        onValueChange = { onEvent(MainViewModel.Event.UpdateUrl(it)) },
                        label = { Text("Series URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = isIdle,
                        trailingIcon = {
                            if (state.seriesUrl.isNotBlank()) {
                                IconButton(
                                    onClick = { onEvent(MainViewModel.Event.ClearDownloadInputs) },
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
                            onClick = { onEvent(MainViewModel.Event.PickLocalFile) },
                            enabled = isIdle
                        ) {
                            Text("Update Local File...")
                        }

                        Button(
                            onClick = { onEvent(MainViewModel.Event.FetchChapters) },
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
                        onValueChange = { onEvent(MainViewModel.Event.UpdateCustomTitle(it)) },
                        label = { Text("Output Filename (without extension)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isProcessing
                    )

                    OutlinedTextField(
                        value = state.outputPath,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateOutputPath(it)) },
                        label = { Text("Output Directory") },
                        placeholder = { Text("Default: Your Downloads folder") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isProcessing
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
                                onValueChange = { onEvent(MainViewModel.Event.UpdateWorkers(it)) },
                                range = 1..16,
                                enabled = isProcessing
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { formatDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isProcessing
                            ) {
                                Text("Format: ${state.outputFormat.uppercase()}")
                                Icon(Icons.Default.ArrowDropDown, "Format")
                            }
                            DropdownMenu(
                                expanded = formatDropdownExpanded,
                                onDismissRequest = { formatDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(onClick = {
                                    onEvent(MainViewModel.Event.UpdateFormat("cbz"))
                                    formatDropdownExpanded = false
                                }) { Text("CBZ") }
                                DropdownMenuItem(onClick = {
                                    onEvent(MainViewModel.Event.UpdateFormat("epub"))
                                    formatDropdownExpanded = false
                                }) { Text("EPUB") }
                            }
                        }
                    }
                }
            }
        }

        if (isRunning) {
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
        } else {
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.operationState) {
                OperationState.IDLE -> {
                    val buttonText = if (state.sourceFilePath != null) "Sync & Update File" else "Start Download"
                    Button(
                        onClick = { onEvent(MainViewModel.Event.RequestStartOperation) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.fetchedChapters.any { it.selectedSource != null }
                    ) {
                        Text(buttonText)
                    }
                }
                OperationState.RUNNING -> {
                    Button(
                        onClick = { onEvent(MainViewModel.Event.PauseOperation) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pause")
                    }
                    Button(
                        onClick = { onEvent(MainViewModel.Event.RequestCancelOperation) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                OperationState.PAUSED -> {
                    Button(
                        onClick = { onEvent(MainViewModel.Event.ResumeOperation) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Resume")
                    }
                    Button(
                        onClick = { onEvent(MainViewModel.Event.RequestCancelOperation) },
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

@Composable
private fun SearchResultItem(
    result: SearchResult,
    onExpandToggle: () -> Unit,
    onSelect: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandToggle() }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.title, style = MaterialTheme.typography.body1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (result.isFetchingDetails) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading chapters...", style = MaterialTheme.typography.caption)
                    } else {
                        Text(
                            text = "${result.chapterCount ?: 0} chapters",
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        result.chapterRange?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PlatformTooltip("Select this series for download") {
                IconButton(onClick = onSelect) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Select this series")
                }
            }
            PlatformTooltip(if (result.isExpanded) "Collapse" else "Expand to see chapters") {
                Icon(
                    imageVector = if (result.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse"
                )
            }
        }
        AnimatedVisibility(result.isExpanded && !result.isFetchingDetails) {
            Column(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (result.chapters.isEmpty()) {
                    Text("No chapters found for this entry.", style = MaterialTheme.typography.body2)
                } else {
                    result.chapters.forEach { chapter ->
                        Text("• ${chapter.second}", style = MaterialTheme.typography.body2)
                    }
                }
            }
        }
    }
}
