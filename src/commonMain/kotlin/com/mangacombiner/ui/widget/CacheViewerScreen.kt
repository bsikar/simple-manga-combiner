package com.mangacombiner.ui.widget

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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.CachedSeries
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.RangeAction
import com.mangacombiner.ui.viewmodel.Screen
import com.mangacombiner.ui.viewmodel.UiState

@Composable
fun CacheViewerScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onEvent(MainViewModel.Event.Navigate(Screen.SETTINGS)) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings")
            }
            Text("Cached Downloads", style = MaterialTheme.typography.h5, modifier = Modifier.weight(1f))
            IconButton(onClick = { onEvent(MainViewModel.Event.RefreshCacheView) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Cache List")
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
                items(state.cacheContents) { series ->
                    CacheSeriesItem(
                        series = series,
                        selectedPaths = state.cacheItemsToDelete,
                        onEvent = onEvent
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onEvent(MainViewModel.Event.RequestDeleteSelectedCacheItems) },
                enabled = state.cacheItemsToDelete.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
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
    selectedPaths: Set<String>,
    onEvent: (MainViewModel.Event) -> Unit
) {
    var rangeStart by remember { mutableStateOf("") }
    var rangeEnd by remember { mutableStateOf("") }

    Card(elevation = 4.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Series Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selectedPaths.contains(series.path),
                    onCheckedChange = { onEvent(MainViewModel.Event.ToggleCacheItemForDeletion(series.path)) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(series.seriesName, style = MaterialTheme.typography.h6)
                    Text(
                        "${series.chapters.size} chapter(s) - ${series.totalSizeFormatted}",
                        style = MaterialTheme.typography.caption
                    )
                }
            }

            // Selection Controls
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

            // Chapter List
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                series.chapters.forEachIndexed { index, chapter ->
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
