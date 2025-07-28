package com.mangacombiner.ui.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.Book
import com.mangacombiner.service.EpubReaderService
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.ReaderTheme
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.bytesToImageBitmap
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
private fun PageImage(filePath: String, imageHref: String, modifier: Modifier = Modifier) {
    val epubReaderService = koinInject<EpubReaderService>()
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(filePath, imageHref) {
        isLoading = true
        val bytes = epubReaderService.extractImage(filePath, imageHref)
        if (bytes != null) {
            bitmap = bytesToImageBitmap(bytes)
        }
        isLoading = false
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator()
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "Page",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Text("Failed to load image")
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun ReaderScreen(state: UiState, onEvent: (Event) -> Unit) {
    val book = state.currentBook ?: return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentPageInBook - 1).coerceAtLeast(0))

    // As the user scrolls, this effect updates the state and saves progress
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { it + 1 }
            .distinctUntilChanged()
            .debounce(500) // Persist progress after user stops scrolling for 500ms
            .collect { newPage ->
                var pagesCounted = 0
                var chapterIdx = 0
                for ((idx, chap) in book.chapters.withIndex()) {
                    val chapterSize = chap.imageHrefs.size
                    if (newPage > pagesCounted && newPage <= pagesCounted + chapterSize) {
                        chapterIdx = idx
                        break
                    }
                    pagesCounted += chapterSize
                }
                onEvent(Event.Library.UpdateProgress(newPage, chapterIdx))
            }
    }

    // This effect ensures the view scrolls when the page number is changed programmatically
    LaunchedEffect(state.currentPageInBook) {
        val targetIndex = (state.currentPageInBook - 1).coerceAtLeast(0)
        if (listState.firstVisibleItemIndex != targetIndex) {
            listState.scrollToItem(targetIndex)
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
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(Event.Library.CloseBook) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Library")
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colors.surface.copy(alpha = 0.95f))) {
                ReaderControlRow(state, onEvent)
                ReaderProgressBar(state, onEvent, listState)
            }
        }
    ) { padding ->
        val allImageHrefs = remember(book) { book.chapters.flatMap { it.imageHrefs } }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(backgroundColor).padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(allImageHrefs, key = { index, _ -> index }) { index, imageHref ->
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 200.dp)) {
                    val horizontalScrollState = rememberScrollState()
                    val imageWidth = maxWidth * state.readerImageScale

                    LaunchedEffect(imageWidth, maxWidth) {
                        if (imageWidth > maxWidth) {
                            val maxScroll = horizontalScrollState.maxValue
                            horizontalScrollState.scrollTo(maxScroll / 2)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (imageWidth > maxWidth) Modifier.horizontalScroll(horizontalScrollState) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        PageImage(
                            filePath = book.localCachePath ?: book.filePath, // Use the cached path on Android
                            imageHref = imageHref,
                            modifier = Modifier.width(imageWidth)
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun ReaderControlRow(state: UiState, onEvent: (Event) -> Unit) {
    val book = state.currentBook ?: return
    var themeMenuExpanded by remember { mutableStateOf(false) }

    var pageInChapter = 0
    var pagesCounted = 0
    for ((idx, chap) in book.chapters.withIndex()) {
        val chapterSize = chap.imageHrefs.size
        if (state.currentChapterIndex == idx) {
            pageInChapter = state.currentPageInBook - pagesCounted
            break
        }
        pagesCounted += chapterSize
    }
    val chapterTitle = book.chapters.getOrNull(state.currentChapterIndex)?.title ?: "Chapter ${state.currentChapterIndex + 1}"
    val progressPercent = if (state.totalPagesInBook > 0) (state.currentPageInBook * 100 / state.totalPagesInBook) else 0

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left controls
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { // Negative spacing to tighten icons
                IconButton(onClick = { onEvent(Event.Library.ToggleToc) }) { Icon(Icons.AutoMirrored.Filled.ListAlt, "Table of Contents") }
                IconButton(onClick = { onEvent(Event.Library.PreviousChapter) }, enabled = state.currentChapterIndex > 0) { Icon(Icons.Default.SkipPrevious, "Previous Chapter") }
                IconButton(onClick = { onEvent(Event.Library.NextChapter) }, enabled = state.currentChapterIndex < book.chapters.lastIndex) { Icon(Icons.Default.SkipNext, "Next Chapter") }
            }

            // Right controls
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                IconButton(onClick = { onEvent(Event.Library.ZoomOut) }) { Icon(Icons.Default.ZoomOut, "Zoom Out") }
                IconButton(onClick = { onEvent(Event.Library.ResetImageScale) }) { Icon(Icons.Default.ZoomOutMap, "Reset Zoom") }
                IconButton(onClick = { onEvent(Event.Library.ZoomIn) }) { Icon(Icons.Default.ZoomIn, "Zoom In") }
                Box {
                    IconButton(onClick = { themeMenuExpanded = true }) { Icon(Icons.Default.Palette, "Change Theme") }
                    DropdownMenu(expanded = themeMenuExpanded, onDismissRequest = { themeMenuExpanded = false }) {
                        DropdownMenuItem(onClick = { onEvent(Event.Library.ChangeReaderTheme(ReaderTheme.BLACK)); themeMenuExpanded = false }) { Text("Black") }
                        DropdownMenuItem(onClick = { onEvent(Event.Library.ChangeReaderTheme(ReaderTheme.WHITE)); themeMenuExpanded = false }) { Text("White") }
                        DropdownMenuItem(onClick = { onEvent(Event.Library.ChangeReaderTheme(ReaderTheme.SEPIA)); themeMenuExpanded = false }) { Text("Sepia") }
                    }
                }
            }
        }
        Text(
            text = "$chapterTitle - Page $pageInChapter ($progressPercent%)",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.caption
        )
    }
}

@Composable
private fun ReaderProgressBar(state: UiState, onEvent: (Event) -> Unit, listState: LazyListState) {
    var sliderPosition by remember(state.currentPageInBook) { mutableStateOf(state.currentPageInBook.toFloat()) }
    val coroutineScope = rememberCoroutineScope()

    Slider(
        value = sliderPosition,
        onValueChange = { sliderPosition = it },
        onValueChangeFinished = {
            val targetPage = sliderPosition.roundToInt()
            onEvent(Event.Library.GoToPage(targetPage))
            coroutineScope.launch {
                listState.scrollToItem((targetPage - 1).coerceAtLeast(0))
            }
        },
        valueRange = 1f..(state.totalPagesInBook.toFloat().coerceAtLeast(1f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp).height(24.dp)
    )
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TableOfContentsDrawer(
    book: Book,
    currentChapterIndex: Int,
    onEvent: (Event) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Table of Contents") },
            navigationIcon = {
                IconButton(onClick = { onEvent(Event.Library.ToggleToc) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Table of Contents")
                }
            },
            backgroundColor = MaterialTheme.colors.surface,
            elevation = 4.dp
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            var pageCounter = 0
            itemsIndexed(book.chapters) { index, chapter ->
                val isCurrentChapter = index == currentChapterIndex
                val startPage = pageCounter + 1
                val endPage = pageCounter + chapter.imageHrefs.size

                ListItem(
                    modifier = Modifier
                        .clickable {
                            onEvent(Event.Library.GoToPage(startPage))
                            onEvent(Event.Library.ToggleToc)
                        }
                        .background(if (isCurrentChapter) MaterialTheme.colors.primary.copy(alpha = 0.2f) else Color.Transparent),
                    text = {
                        Text(
                            text = chapter.title,
                            fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrentChapter) MaterialTheme.colors.primary else LocalContentColor.current
                        )
                    },
                    secondaryText = {
                        Text("Pages $startPage - $endPage")
                    }
                )

                pageCounter = endPage
            }
        }
    }
}
