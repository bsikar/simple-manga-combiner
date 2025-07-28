package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.Book
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.ReaderTheme
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.bytesToImageBitmap
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
@Composable
fun ReaderScreen(state: UiState, onEvent: (Event) -> Unit) {
    val book = state.currentBook ?: return
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // As the user scrolls, this effect updates the state and saves progress
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { it + 1 }
            .distinctUntilChanged()
            .debounce(1000)
            .collect { newPage ->
                var pagesCounted = 0
                var chapterIdx = 0
                for ((idx, chap) in book.chapters.withIndex()) {
                    if (newPage > pagesCounted && newPage <= pagesCounted + chap.imageResources.size) {
                        chapterIdx = idx
                        break
                    }
                    pagesCounted += chap.imageResources.size
                }
                onEvent(Event.Library.UpdateProgress(newPage, chapterIdx))
            }
    }

    val backgroundColor = when (state.readerTheme) {
        ReaderTheme.BLACK -> Color.Black
        ReaderTheme.WHITE -> Color.White
        ReaderTheme.SEPIA -> Color(0xFFFBF0D9)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(Event.Library.CloseBook) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Library")
                    }
                }
            )
        },
        bottomBar = { ReaderControls(state, onEvent, listState) }
    ) { padding ->
        val allImages = remember(book) { book.chapters.flatMap { it.imageResources } }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(backgroundColor).padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(allImages) { index, imageData ->
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val bitmap = remember(imageData) { bytesToImageBitmap(imageData) }

                    if (state.readerImageScale <= 1.0f) {
                        // --- ZOOM OUT MODE (Centered) ---
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier.fillMaxWidth(state.readerImageScale),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    } else {
                        // --- ZOOM IN MODE (Pannable with Scrollbar) ---
                        val horizontalScrollState = rememberScrollState()
                        LaunchedEffect(Unit) {
                            // Center the image when it first appears zoomed in
                            horizontalScrollState.scrollTo(horizontalScrollState.maxValue / 2)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(horizontalScrollState),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier.width(this@BoxWithConstraints.maxWidth * state.readerImageScale),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderControls(state: UiState, onEvent: (Event) -> Unit, listState: LazyListState) {
    val book = state.currentBook ?: return
    var themeMenuExpanded by remember { mutableStateOf(false) }
    var showZoomSlider by remember { mutableStateOf(false) }

    // Local state for the slider to provide instant visual feedback while dragging
    var sliderPosition by remember(state.currentPageInBook) { mutableStateOf(state.currentPageInBook.toFloat()) }
    val progressPercent = if (state.totalPagesInBook > 0) (sliderPosition * 100 / state.totalPagesInBook).roundToInt() else 0
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.background(MaterialTheme.colors.surface)) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onEvent(Event.Library.PreviousChapter) },
                enabled = state.currentChapterIndex > 0
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous Chapter") }

            Text(
                text = book.chapters.getOrNull(state.currentChapterIndex)?.title ?: "",
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            IconButton(
                onClick = { onEvent(Event.Library.NextChapter) },
                enabled = state.currentChapterIndex < book.chapters.lastIndex
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next Chapter") }

            IconButton(onClick = { onEvent(Event.Library.ResetImageScale) }) {
                Icon(Icons.Default.ZoomOutMap, "Reset Zoom")
            }

            IconButton(onClick = { showZoomSlider = !showZoomSlider }) {
                Icon(Icons.Default.ZoomIn, "Zoom")
            }

            Box {
                IconButton(onClick = { themeMenuExpanded = true }) {
                    Icon(Icons.Default.Palette, "Change Theme")
                }
                DropdownMenu(expanded = themeMenuExpanded, onDismissRequest = { themeMenuExpanded = false }) {
                    DropdownMenuItem(onClick = { onEvent(Event.Library.ChangeReaderTheme(ReaderTheme.BLACK)); themeMenuExpanded = false }) { Text("Black") }
                    DropdownMenuItem(onClick = { onEvent(Event.Library.ChangeReaderTheme(ReaderTheme.WHITE)); themeMenuExpanded = false }) { Text("White") }
                    DropdownMenuItem(onClick = { onEvent(Event.Library.ChangeReaderTheme(ReaderTheme.SEPIA)); themeMenuExpanded = false }) { Text("Sepia") }
                }
            }
        }
        AnimatedVisibility(visible = showZoomSlider) {
            Slider(
                value = state.readerImageScale,
                onValueChange = { onEvent(Event.Library.ChangeImageScale(it)) },
                valueRange = 0.1f..3.0f,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Page ${sliderPosition.roundToInt()} / ${state.totalPagesInBook} ($progressPercent%)",
                style = MaterialTheme.typography.caption
            )
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = {
                    val targetPage = sliderPosition.roundToInt()
                    onEvent(Event.Library.GoToPage(targetPage))
                    // Manually trigger scroll for instant response
                    coroutineScope.launch {
                        listState.scrollToItem((targetPage - 1).coerceAtLeast(0))
                    }
                },
                valueRange = 1f..(state.totalPagesInBook.toFloat().coerceAtLeast(1f)),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
