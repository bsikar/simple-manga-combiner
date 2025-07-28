package com.mangacombiner.ui.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.bytesToImageBitmap

@Composable
fun LibraryScreen(state: UiState, onEvent: (Event) -> Unit) {
    LaunchedEffect(Unit) {
        onEvent(Event.Library.ScanForBooks)
    }

    if (state.currentBook != null) {
        ReaderScreen(state, onEvent)
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = MaterialTheme.typography.h5, modifier = Modifier.weight(1f))
                PlatformTooltip("Scan a Different Folder") {
                    IconButton(onClick = { onEvent(Event.Library.ScanCustomFolder) }) {
                        Icon(Icons.Default.FolderOpen, "Scan Different Folder")
                    }
                }
                PlatformTooltip("Refresh Default Folder") {
                    IconButton(onClick = { onEvent(Event.Library.ScanForBooks) }) {
                        Icon(Icons.Default.Refresh, "Refresh Library")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (state.isLibraryLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.libraryBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No EPUBs found in your output directory.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.libraryBooks, key = { it.filePath }) { book ->
                        Card(
                            modifier = Modifier.clickable { onEvent(Event.Library.OpenBook(book.filePath)) },
                            elevation = 4.dp
                        ) {
                            Column {
                                if (book.coverImage != null) {
                                    val bitmap = remember(book.coverImage) { bytesToImageBitmap(book.coverImage) }
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = book.title,
                                        modifier = Modifier.height(200.dp).fillMaxWidth(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(modifier = Modifier.height(200.dp).fillMaxWidth()) // Placeholder
                                }
                                Text(
                                    text = book.title,
                                    style = MaterialTheme.typography.subtitle2,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
