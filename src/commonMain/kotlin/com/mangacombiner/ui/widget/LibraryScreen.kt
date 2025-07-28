package com.mangacombiner.ui.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.bytesToImageBitmap

@Composable
fun LibraryScreen(state: UiState, onEvent: (Event) -> Unit) {
    // Automatically scan for books when the screen is first displayed
    LaunchedEffect(Unit) {
        if (state.libraryBooks.isEmpty()) {
            onEvent(Event.Library.ScanForBooks)
        }
    }

    if (state.currentBook != null) {
        // If a book is opened, show the reader screen
        ReaderScreen(state, onEvent)
    } else {
        // Otherwise, show the library grid
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = MaterialTheme.typography.h5, modifier = Modifier.weight(1f))
                PlatformTooltip("Open EPUB File") {
                    IconButton(onClick = { onEvent(Event.Library.OpenFileDirectly) }) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, "Open EPUB File")
                    }
                }
                PlatformTooltip("Refresh Library") {
                    IconButton(onClick = { onEvent(Event.Library.ScanForBooks) }) {
                        Icon(Icons.Default.Refresh, "Refresh Library")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            when {
                state.isLibraryLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.libraryBooks.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Your library is empty.")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onEvent(Event.Library.ScanForBooks) }) {
                                Text("Scan for EPUBs")
                            }
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp), // Use a smaller minimum size for more columns
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.libraryBooks, key = { it.filePath }) { book ->
                            Card(
                                modifier = Modifier.clickable { onEvent(Event.Library.OpenBook(book.filePath)) },
                                elevation = 4.dp
                            ) {
                                Box {
                                    Column {
                                        // Book Cover Image
                                        if (book.coverImage != null) {
                                            val bitmap = remember(book.coverImage) { bytesToImageBitmap(book.coverImage) }
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = book.title,
                                                modifier = Modifier.height(160.dp).fillMaxWidth(), // Reduced height
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            // Placeholder for books without a cover
                                            Box(modifier = Modifier.height(160.dp).fillMaxWidth().background(MaterialTheme.colors.surface.copy(alpha = 0.5f)))
                                        }

                                        // Book Title (no scrim, just plain text below)
                                        Text(
                                            text = book.title,
                                            modifier = Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.caption, // Smaller font
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
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
}
