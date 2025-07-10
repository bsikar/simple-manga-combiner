package com.mangacombiner.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.ui.viewmodel.state.RangeAction
import com.mangacombiner.ui.viewmodel.state.UiState

@Composable
private fun StatusIndicator(text: String, icon: ImageVector, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "($text)",
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterSelectionDialog(state: UiState, onEvent: (Event) -> Unit) {
    val selectedCount = state.fetchedChapters.count { it.selectedSource != null }
    var rangeStart by remember { mutableStateOf("") }
    var rangeEnd by remember { mutableStateOf("") }
    val hasCachedChapters = state.fetchedChapters.any { it.availableSources.contains(ChapterSource.CACHE) }
    val hasLocalChapters = state.fetchedChapters.any { it.availableSources.contains(ChapterSource.LOCAL) }
    val isSyncMode = state.sourceFilePath != null

    Dialog(onDismissRequest = { onEvent(Event.Download.CancelChapterSelection) }) {
        Surface(
            modifier = Modifier.width(600.dp).heightIn(max = 700.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Available Chapters", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(16.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { onEvent(Event.Download.SelectAllChapters) }) { Text("Select All") }
                    Button(onClick = { onEvent(Event.Download.DeselectAllChapters) }) { Text("Deselect All") }

                    if (hasLocalChapters) {
                        Button(onClick = { onEvent(Event.Download.UseAllLocal) }) { Text("Use All (Local)") }
                        Button(onClick = { onEvent(Event.Download.IgnoreAllLocal) }) { Text("Ignore All (Local)") }
                        Button(onClick = { onEvent(Event.Download.RedownloadAllLocal) }) { Text("Re-download All (Local)") }
                    }
                    if (hasCachedChapters) {
                        Button(onClick = { onEvent(Event.Download.UseAllCached) }) { Text("Use All (Cached)") }
                        Button(onClick = { onEvent(Event.Download.IgnoreAllCached) }) { Text("Ignore All (Cached)") }
                        Button(onClick = { onEvent(Event.Download.RedownloadAllCached) }) { Text("Re-download All (Cached)") }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = rangeStart,
                        onValueChange = { rangeStart = it.filter { c -> c.isDigit() } },
                        label = { Text("Start") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = rangeEnd,
                        onValueChange = { rangeEnd = it.filter { c -> c.isDigit() } },
                        label = { Text("End") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val rangeIsSet = rangeStart.isNotBlank() && rangeEnd.isNotBlank()
                    Button(
                        onClick = { onEvent(Event.Download.UpdateChapterRange(rangeStart.toInt(), rangeEnd.toInt(), RangeAction.SELECT)) },
                        enabled = rangeIsSet,
                        modifier = Modifier.weight(1f)
                    ) { Text("Select") }
                    Button(
                        onClick = { onEvent(Event.Download.UpdateChapterRange(rangeStart.toInt(), rangeEnd.toInt(), RangeAction.DESELECT)) },
                        enabled = rangeIsSet,
                        modifier = Modifier.weight(1f)
                    ) { Text("Deselect") }
                    Button(
                        onClick = { onEvent(Event.Download.UpdateChapterRange(rangeStart.toInt(), rangeEnd.toInt(), RangeAction.TOGGLE)) },
                        enabled = rangeIsSet,
                        modifier = Modifier.weight(1f)
                    ) { Text("Toggle") }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(state.fetchedChapters, key = { _, chapter -> chapter.url }) { index, chapter ->
                        val isLocal = chapter.availableSources.contains(ChapterSource.LOCAL)
                        val isCached = chapter.availableSources.contains(ChapterSource.CACHE)
                        val isSelected = chapter.selectedSource != null

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onEvent(Event.Download.ToggleChapterSelection(chapter.url, it)) }
                                )
                                Text(
                                    text = "${index + 1}. ${chapter.title}",
                                    style = MaterialTheme.typography.body1,
                                    modifier = Modifier.weight(1f),
                                    color = if (isSelected) MaterialTheme.colors.onSurface else MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
                                )
                            }

                            if (isLocal || isCached) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        if (isLocal) {
                                            StatusIndicator("Local", Icons.Default.Save, MaterialTheme.colors.primary)
                                        }
                                        if (isCached) {
                                            StatusIndicator("Cached", Icons.Default.Cloud, MaterialTheme.colors.secondary)
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Re-download:", style = MaterialTheme.typography.body2)
                                        Spacer(Modifier.width(4.dp))
                                        Switch(
                                            checked = chapter.selectedSource == ChapterSource.WEB,
                                            onCheckedChange = { onEvent(Event.Download.ToggleChapterRedownload(chapter.url)) },
                                            enabled = isSelected
                                        )
                                    }
                                }
                            }
                        }

                        if (index < state.fetchedChapters.lastIndex) {
                            Divider()
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onEvent(Event.Download.CancelChapterSelection) }) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onEvent(Event.Download.ConfirmChapterSelection) },
                        enabled = selectedCount > 0
                    ) {
                        val buttonText = if (isSyncMode) "Confirm Selections" else "Confirm ($selectedCount Selected)"
                        Text(buttonText)
                    }
                }
            }
        }
    }
}
