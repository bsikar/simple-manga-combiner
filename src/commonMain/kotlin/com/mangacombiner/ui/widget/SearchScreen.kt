package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(state: UiState, onEvent: (Event) -> Unit) {
    var sortDropdownExpanded by remember { mutableStateOf(false) }
    val isIdle = state.operationState == OperationState.IDLE

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Search for a Series", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(8.dp))
                SubmitTextField(
                    value = state.searchQuery,
                    onValueChange = { onEvent(Event.Search.UpdateQuery(it)) },
                    label = { Text("Search on MangaRead.org") },
                    onSubmit = { onEvent(Event.Search.Perform) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isIdle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
    }
}
