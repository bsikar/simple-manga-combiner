package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.*
import com.mangacombiner.util.CachedChapterNameComparator

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CacheViewerScreen(state: UiState, onEvent: (Event) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onEvent(Event.Navigate(Screen.SETTINGS)) }) {
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
            IconButton(onClick = { onEvent(Event.Cache.RefreshView) }) {
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
                    onClick = { onEvent(Event.Cache.SelectAll) },
                    modifier = Modifier.weight(1f)
                ) { Text("Select All") }
                Button(
                    onClick = { onEvent(Event.Cache.DeselectAll) },
                    modifier = Modifier.weight(1f)
                ) { Text("Deselect All") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onEvent(Event.Cache.RequestDeleteSelected) },
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
            onDismissRequest = { onEvent(Event.Cache.CancelDeleteSelected) },
            title = { Text("Confirm Deletion") },
            text = { Text("Are you sure you want to delete the ${state.cacheItemsToDelete.size} selected cache item(s)? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { onEvent(Event.Cache.ConfirmDeleteSelected) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(Event.Cache.CancelDeleteSelected) }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheSeriesItem(
    series: CachedSeries,
    isExpanded: Boolean,
    selectedPaths: Set<String>,
    sortState: CacheSortState?,
    onEvent: (Event) -> Unit
) {
    var rangeStart by remember { mutableStateOf("") }
    var rangeEnd by remember { mutableStateOf("") }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val seriesChapterPaths = remember(series.chapters) { series.chapters.map { it.path }.toSet() }
    val selectedChaptersInSeries = remember(selectedPaths, seriesChapterPaths) {
        seriesChapterPaths.intersect(selectedPaths)
    }

    Card(elevation = 4.dp) {
        Column {
            Row(
                modifier = Modifier
                    .clickable { onEvent(Event.Cache.ToggleSeries(series.path)) }
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = seriesChapterPaths.isNotEmpty() && selectedPaths.containsAll(seriesChapterPaths),
                    onCheckedChange = { onEvent(Event.Cache.SelectAllChapters(series.path, it)) }
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        series.seriesName,
                        style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${series.chapters.size} chapter(s) - ${series.totalSizeFormatted}",
                        style = MaterialTheme.typography.caption
                    )
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
                            onEvent(Event.Cache.SetSort(series.path, null))
                            sortMenuExpanded = false
                        }) { Text("Default Order") }
                        DropdownMenuItem(onClick = {
                            onEvent(Event.Cache.SetSort(series.path, CacheSortState(SortCriteria.NAME, SortDirection.ASC)))
                            sortMenuExpanded = false
                        }) { Text("Name (A-Z)") }
                        DropdownMenuItem(onClick = {
                            onEvent(Event.Cache.SetSort(series.path, CacheSortState(SortCriteria.NAME, SortDirection.DESC)))
                            sortMenuExpanded = false
                        }) { Text("Name (Z-A)") }
                        DropdownMenuItem(onClick = {
                            onEvent(Event.Cache.SetSort(series.path, CacheSortState(SortCriteria.SIZE, SortDirection.ASC)))
                            sortMenuExpanded = false
                        }) { Text("Size (Smallest First)") }
                        DropdownMenuItem(onClick = {
                            onEvent(Event.Cache.SetSort(series.path, CacheSortState(SortCriteria.SIZE, SortDirection.DESC)))
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEvent(Event.Cache.SelectAllChapters(series.path, true)) }) { Text("Select All") }
                            Button(onClick = { onEvent(Event.Cache.SelectAllChapters(series.path, false)) }) { Text("Deselect All") }
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
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val startInt = rangeStart.toIntOrNull()
                            val endInt = rangeEnd.toIntOrNull()
                            val rangeIsSet = startInt != null && endInt != null && startInt <= endInt && endInt <= series.chapters.size
                            Button(
                                onClick = { onEvent(Event.Cache.UpdateChapterRange(series.path, startInt!!, endInt!!, RangeAction.SELECT)) },
                                enabled = rangeIsSet,
                            ) { Text("Select") }
                            Button(
                                onClick = { onEvent(Event.Cache.UpdateChapterRange(series.path, startInt!!, endInt!!, RangeAction.DESELECT)) },
                                enabled = rangeIsSet,
                            ) { Text("Deselect") }
                        }
                    }

                    if (series.seriesUrl != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { onEvent(Event.Cache.LoadCachedSeries(series.path)) },
                                enabled = selectedChaptersInSeries.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Import", modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("Import to Downloader (${selectedChaptersInSeries.size})")
                            }
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEvent(Event.Cache.ToggleItemForDeletion(chapter.path)) }
                                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedPaths.contains(chapter.path),
                                    onCheckedChange = { onEvent(Event.Cache.ToggleItemForDeletion(chapter.path)) }
                                )
                                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "${index + 1}. ${chapter.name}",
                                            style = MaterialTheme.typography.body1
                                        )
                                        if (chapter.isBroken) {
                                            Icon(
                                                imageVector = Icons.Default.SyncProblem,
                                                contentDescription = "Broken Chapter",
                                                tint = MaterialTheme.colors.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${chapter.pageCount} pages (${chapter.sizeFormatted})",
                                        style = MaterialTheme.typography.body2,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
