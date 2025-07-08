package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.RangeAction
import com.mangacombiner.ui.viewmodel.UiState

@Composable
fun ChapterSelectionDialog(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    val selectedCount = state.fetchedChapters.count { it.isSelected }
    var rangeStart by remember { mutableStateOf("") }
    var rangeEnd by remember { mutableStateOf("") }
    val hasCachedChapters = state.fetchedChapters.any { it.isCached }

    Dialog(onDismissRequest = { onEvent(MainViewModel.Event.CancelChapterSelection) }) {
        Surface(
            modifier = Modifier.width(600.dp).heightIn(max = 700.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Available Chapters", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onEvent(MainViewModel.Event.SelectAllChapters) }) {
                            Text("Select All")
                        }
                        Button(onClick = { onEvent(MainViewModel.Event.DeselectAllChapters) }) {
                            Text("Deselect All")
                        }
                    }
                    if (hasCachedChapters) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEvent(MainViewModel.Event.SelectAllCacheOverrides) }) {
                                Text("Re-download All (Cached)")
                            }
                            Button(onClick = { onEvent(MainViewModel.Event.DeselectAllCacheOverrides) }) {
                                Text("Use All (Cached)")
                            }
                            Button(onClick = { onEvent(MainViewModel.Event.ToggleAllCacheOverrides) }) {
                                Text("Toggle All (Cached)")
                            }
                        }
                    }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        val rangeIsSet = rangeStart.isNotBlank() && rangeEnd.isNotBlank()
                        Button(
                            onClick = {
                                onEvent(
                                    MainViewModel.Event.UpdateChapterRange(
                                        rangeStart.toInt(), rangeEnd.toInt(), RangeAction.SELECT
                                    )
                                )
                            },
                            enabled = rangeIsSet,
                            modifier = Modifier.weight(1f)
                        ) { Text("Select") }
                        Button(
                            onClick = {
                                onEvent(
                                    MainViewModel.Event.UpdateChapterRange(
                                        rangeStart.toInt(), rangeEnd.toInt(), RangeAction.DESELECT
                                    )
                                )
                            },
                            enabled = rangeIsSet,
                            modifier = Modifier.weight(1f)
                        ) { Text("Deselect") }
                        Button(
                            onClick = {
                                onEvent(
                                    MainViewModel.Event.UpdateChapterRange(
                                        rangeStart.toInt(), rangeEnd.toInt(), RangeAction.TOGGLE
                                    )
                                )
                            },
                            enabled = rangeIsSet,
                            modifier = Modifier.weight(1f)
                        ) { Text("Toggle") }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(state.fetchedChapters, key = { _, chapter -> chapter.url }) { index, chapter ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = chapter.isSelected,
                                onCheckedChange = { onEvent(MainViewModel.Event.ToggleChapterSelection(chapter.url, !chapter.isSelected)) }
                            )
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.body1,
                                modifier = Modifier.weight(1f),
                                color = if (chapter.isCached) MaterialTheme.colors.primary else LocalContentColor.current
                            )
                            if (chapter.isCached) {
                                Text("(Cached)", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Re-download:", style = MaterialTheme.typography.body2)
                                Spacer(Modifier.width(4.dp))
                                Switch(
                                    checked = chapter.url in state.chapterCacheOverrides,
                                    onCheckedChange = { onEvent(MainViewModel.Event.ToggleChapterCacheOverride(chapter.url)) }
                                )
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
                    TextButton(onClick = { onEvent(MainViewModel.Event.CancelChapterSelection) }) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onEvent(MainViewModel.Event.ConfirmChapterSelection) },
                        enabled = selectedCount > 0
                    ) {
                        Text("Confirm ($selectedCount Selected)")
                    }
                }
            }
        }
    }
}
