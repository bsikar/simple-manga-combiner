package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.ui.viewmodel.state.SearchSortOption
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.pointer.tooltipHoverFix

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterialApi::class)
@Composable
fun SearchScreen(state: UiState, onEvent: (Event) -> Unit) {
    var sortDropdownExpanded by remember { mutableStateOf(false) }
    var sourceDropdownExpanded by remember { mutableStateOf(false) }
    val isIdle = state.operationState == OperationState.IDLE
    val listState = rememberLazyListState()

    LaunchedEffect(state.searchResults) {
        if (state.searchResults.isNotEmpty()) {
            listState.animateScrollToItem(index = 0)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Search for a Series", style = MaterialTheme.typography.h6)

                ExposedDropdownMenuBox(
                    expanded = sourceDropdownExpanded,
                    onExpandedChange = {
                        if (isIdle) sourceDropdownExpanded = !sourceDropdownExpanded
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = state.searchSource,
                        onValueChange = { },
                        label = { Text("Search Source") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle
                    )
                    ExposedDropdownMenu(
                        expanded = sourceDropdownExpanded,
                        onDismissRequest = { sourceDropdownExpanded = false }
                    ) {
                        state.searchSources.forEach { sourceName ->
                            DropdownMenuItem(onClick = {
                                onEvent(Event.Search.UpdateSource(sourceName))
                                sourceDropdownExpanded = false
                            }) {
                                Text(text = sourceName)
                            }
                        }
                    }
                }

                SubmitTextField(
                    value = state.searchQuery,
                    onValueChange = { onEvent(Event.Search.UpdateQuery(it)) },
                    label = { Text("Search on ${state.searchSource}") },
                    onSubmit = { onEvent(Event.Search.Perform) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isIdle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = {
                        if (state.searchQuery.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    onEvent(Event.Search.UpdateQuery(""))
                                    onEvent(Event.Search.ClearResults)
                                },
                                enabled = isIdle
                            ) {
                                Icon(Icons.Filled.Clear, "Clear Search")
                            }
                        }
                    }
                )
                Button(
                    onClick = {
                        if (state.isSearching) {
                            onEvent(Event.Search.Cancel)
                        } else {
                            onEvent(Event.Search.Perform)
                        }
                    },
                    enabled = (state.searchQuery.isNotBlank() && isIdle) || state.isSearching,
                    colors = if (state.isSearching) {
                        ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                            Text("Cancel")
                        } else {
                            Text("Search")
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.searchResults.isNotEmpty() || state.isSearching, modifier = Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Search Results:", style = MaterialTheme.typography.subtitle1)
                    Box(
                        modifier = Modifier.tooltipHoverFix()
                    ) {
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

                if (state.isSearching && state.searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.searchResults, key = { it.url }) { result ->
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
    }
}
