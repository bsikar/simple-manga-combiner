package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
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

    Dialog(onDismissRequest = { onEvent(MainViewModel.Event.CancelChapterSelection) }) {
        Surface(
            modifier = Modifier.width(600.dp).heightIn(max = 700.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Available Chapters", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(8.dp))

                // Toolbar for actions
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onEvent(MainViewModel.Event.SelectAllChapters) }) {
                            Text("Select All")
                        }
                        Button(onClick = { onEvent(MainViewModel.Event.DeselectAllChapters) }) {
                            Text("Deselect All")
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
                    itemsIndexed(state.fetchedChapters) { index, chapter ->
                        val onToggle: () -> Unit =
                            { onEvent(MainViewModel.Event.ToggleChapterSelection(chapter.url, !chapter.isSelected)) }
                        FormControlLabel(
                            onClick = onToggle,
                            control = {
                                Checkbox(
                                    checked = chapter.isSelected,
                                    onCheckedChange = { onToggle() }
                                )
                            },
                            label = { Text(chapter.title, style = MaterialTheme.typography.body1) }
                        )
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
                    Button(onClick = { onEvent(MainViewModel.Event.ConfirmChapterSelection) }) {
                        Text("Confirm ($selectedCount Selected)")
                    }
                }
            }
        }
    }
}
