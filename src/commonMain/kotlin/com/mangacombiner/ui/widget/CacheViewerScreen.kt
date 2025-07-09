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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.ui.viewmodel.CacheSortState
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.RangeAction
import com.mangacombiner.ui.viewmodel.Screen
import com.mangacombiner.ui.viewmodel.SortCriteria
import com.mangacombiner.ui.viewmodel.SortDirection
import com.mangacombiner.ui.viewmodel.UiState
import com.mangacombiner.util.CachedChapterNameComparator

@Composable
fun CacheViewerScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onEvent(MainViewModel.Event.Navigate(Screen.SETTINGS)) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = MaterialTheme.colors.onBackground
                )
            }
            Text(
                text = "Cached Downloads",
                style = MaterialTheme.typography.h5,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colors.onBackground
            )
            IconButton(onClick = { onEvent(MainViewModel.Event.RefreshCacheView) }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Cache List",
                    tint = MaterialTheme.colors.onBackground
                )
            }
        }

        if (state.cacheContents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No cached items found.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(state.cacheContents, key = { it.path }) { series ->
                    CacheSeriesItem(
                        series = series,
                        isExpanded = series.path in state.expandedCacheSeries,
                        selectedPaths = state.cacheItemsToDelete,
                        sortState = state.cacheSortState[series.path],
                        onEvent = onEvent
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { onEvent(MainViewModel.Event.SelectAllCache) },
                    modifier = Modifier.weight(1f)
                ) { Text("Select All") }
                Button(
                    onClick = { onEvent(MainViewModel.Event.DeselectAllCache) },
                    modifier = Modifier.weight(1f)
                ) { Text("Deselect All") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onEvent(MainViewModel.Event.RequestDeleteSelectedCacheItems) },
                enabled = state.cacheItemsToDelete.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Delete Selected (${state.cacheItemsToDelete.size})")
            }
        }
    }

    if (state.showDeleteCacheConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(MainViewModel.Event.CancelDeleteSelectedCacheItems) },
            title = { Text("Confirm Deletion") },
            text = { Text("Are you sure you want to delete the ${state.cacheItemsToDelete.size} selected cache item(s)? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { onEvent(MainViewModel.Event.ConfirmDeleteSelectedCacheItems) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(MainViewModel.Event.CancelDeleteSelectedCacheItems) }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CacheSeriesItem(
    series: CachedSeries,
    isExpanded: Boolean,
    selectedPaths: Set<String>,
    sortState: CacheSortState?,
    onEvent: (MainViewModel.Event) -> Unit
) {
    var rangeStart by remember { mutableStateOf("") }
    var rangeEnd by remember { mutableStateOf("") }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Card(elevation = 4.dp) {
        Column {
            Row(
                modifier = Modifier
                    .clickable { onEvent(MainViewModel.Event.ToggleCacheSeries(series.path)) }
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val chapterPaths = remember(series.chapters) { series.chapters.map { it.path }.toSet() }
                val isSeriesSelected = chapterPaths.isNotEmpty() && selectedPaths.containsAll(chapterPaths)
                Checkbox(
                    checked = isSeriesSelected,
                    onCheckedChange = { onEvent(MainViewModel.Event.SelectAllCachedChapters(series.path, !isSeriesSelected)) }
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(series.seriesName, style = MaterialTheme.typography.h6)
                    Text(
                        "${series.chapters.size} chapter(s) - ${series.totalSizeFormatted}",
                        style = MaterialTheme.typography.caption
                    )
                }

                // Only show the continue button if a URL was found in the cache
                if (series.seriesUrl != null) {
                    PlatformTooltip("Continue Download") {
                        IconButton(
                            onClick = {
                                onEvent(MainViewModel.Event.ContinueFromCache(series.seriesUrl))
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "Continue Downloading Series")
                        }
                    }
                }

                Box {
                    PlatformTooltip("Sort Chapters") {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort Chapters Menu")
                        }
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        DropdownMenuItem(onClick = {
                            onEvent(MainViewModel.Event.SetCacheSort(series.path, null))
                            sortMenuExpanded = false
                        }) { Text("Default Order") }
                        DropdownMenuItem(onClick = {
                            onEvent(MainViewModel.Event.SetCacheSort(series.path, CacheSortState(SortCriteria.NAME, SortDirection.ASC)))
                            sortMenuExpanded = false
                        }) { Text("Name (A-Z)") }
                        DropdownMenuItem(onClick = {
                            onEvent(MainViewModel.Event.SetCacheSort(series.path, CacheSortState(SortCriteria.NAME, SortDirection.DESC)))
                            sortMenuExpanded = false
                        }) { Text("Name (Z-A)") }
                        DropdownMenuItem(onClick = {
                            onEvent(MainViewModel.Event.SetCacheSort(series.path, CacheSortState(SortCriteria.SIZE, SortDirection.ASC)))
                            sortMenuExpanded = false
                        }) { Text("Size (Smallest First)") }
                        DropdownMenuItem(onClick = {
                            onEvent(MainViewModel.Event.SetCacheSort(series.path, CacheSortState(SortCriteria.SIZE, SortDirection.DESC)))
                            sortMenuExpanded = false
                        }) { Text("Size (Largest First)") }
                    }
                }
                PlatformTooltip(if (isExpanded) "Collapse" else "Expand") {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand"
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEvent(MainViewModel.Event.SelectAllCachedChapters(series.path, true)) }) { Text("Select All") }
                            Button(onClick = { onEvent(MainViewModel.Event.SelectAllCachedChapters(series.path, false)) }) { Text("Deselect All") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = rangeStart,
                                onValueChange = { rangeStart = it.filter(Char::isDigit) },
                                label = { Text("Start") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = rangeEnd,
                                onValueChange = { rangeEnd = it.filter(Char::isDigit) },
                                label = { Text("End") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val startInt = rangeStart.toIntOrNull()
                            val endInt = rangeEnd.toIntOrNull()
                            val rangeIsSet = startInt != null && endInt != null && startInt <= endInt && endInt <= series.chapters.size
                            Button(
                                onClick = { onEvent(MainViewModel.Event.UpdateCachedChapterRange(series.path, startInt!!, endInt!!, RangeAction.SELECT)) },
                                enabled = rangeIsSet,
                                modifier = Modifier.weight(1f)
                            ) { Text("Select") }
                            Button(
                                onClick = { onEvent(MainViewModel.Event.UpdateCachedChapterRange(series.path, startInt!!, endInt!!, RangeAction.DESELECT)) },
                                enabled = rangeIsSet,
                                modifier = Modifier.weight(1f)
                            ) { Text("Deselect") }
                            Button(
                                onClick = { onEvent(MainViewModel.Event.UpdateCachedChapterRange(series.path, startInt!!, endInt!!, RangeAction.TOGGLE)) },
                                enabled = rangeIsSet,
                                modifier = Modifier.weight(1f)
                            ) { Text("Toggle") }
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    val chaptersToDisplay = remember(series.chapters, sortState) {
                        if (sortState == null) {
                            series.chapters
                        } else {
                            val sortedList = when (sortState.criteria) {
                                SortCriteria.NAME -> series.chapters.sortedWith(CachedChapterNameComparator)
                                SortCriteria.SIZE -> series.chapters.sortedBy { it.sizeInBytes }
                            }
                            if (sortState.direction == SortDirection.DESC) {
                                sortedList.reversed()
                            } else {
                                sortedList
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chaptersToDisplay.forEachIndexed { index, chapter ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                                Checkbox(
                                    checked = selectedPaths.contains(chapter.path),
                                    onCheckedChange = { onEvent(MainViewModel.Event.ToggleCacheItemForDeletion(chapter.path)) }
                                )
                                Text("${index + 1}. ${chapter.name}", modifier = Modifier.weight(1f))
                                Text(
                                    "${chapter.pageCount} pages (${chapter.sizeFormatted})",
                                    style = MaterialTheme.typography.body2
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
