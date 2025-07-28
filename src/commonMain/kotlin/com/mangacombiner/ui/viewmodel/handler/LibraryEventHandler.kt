package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun MainViewModel.handleLibraryEvent(event: Event.Library) {
    when (event) {
        is Event.Library.ScanForBooks -> scanForLibraryBooks()
        is Event.Library.OpenFileDirectly -> viewModelScope.launch {
            _state.update { it.copy(filePickerPurpose = FilePickerRequest.FilePurpose.OPEN_DIRECTLY) }
            _filePickerRequest.emit(FilePickerRequest.OpenFile(FilePickerRequest.FilePurpose.OPEN_DIRECTLY))
        }
        is Event.Library.OpenBook -> viewModelScope.launch { openBook(event.bookPath) }
        is Event.Library.CloseBook -> closeBook()
        is Event.Library.NextChapter -> changeChapter(1)
        is Event.Library.PreviousChapter -> changeChapter(-1)
        is Event.Library.ChangeReaderTheme -> _state.update { it.copy(readerTheme = event.theme) }
        is Event.Library.GoToPage -> {
            val book = _state.value.currentBook ?: return
            val newPage = event.page

            // Calculate the corresponding chapter index for the new page
            var pagesCounted = 0
            var newChapterIndex = 0
            for ((idx, chap) in book.chapters.withIndex()) {
                val chapterSize = chap.imageResources.size
                if (newPage > pagesCounted && newPage <= pagesCounted + chapterSize) {
                    newChapterIndex = idx
                    break
                }
                pagesCounted += chapterSize
            }

            // Save progress and atomically update both page and chapter index state
            readingProgressRepository.saveProgress(book.filePath, newPage)
            _state.update {
                it.copy(
                    currentPageInBook = newPage,
                    currentChapterIndex = newChapterIndex
                )
            }
        }
        is Event.Library.ZoomIn -> _state.update { it.copy(readerImageScale = (it.readerImageScale + 0.2f).coerceIn(0.1f, 3.0f)) }
        is Event.Library.ZoomOut -> _state.update { it.copy(readerImageScale = (it.readerImageScale - 0.2f).coerceIn(0.1f, 3.0f)) }
        is Event.Library.ResetImageScale -> _state.update { it.copy(readerImageScale = 1.0f) }
        is Event.Library.UpdateProgress -> {
            // This event is for passively updating the state as the user scrolls
            _state.value.currentBook?.let { book ->
                readingProgressRepository.saveProgress(book.filePath, event.currentPage)
            }
            _state.update { it.copy(currentPageInBook = event.currentPage, currentChapterIndex = event.currentChapterIndex) }
        }
        is Event.Library.ToggleToc -> _state.update { it.copy(showReaderToc = !it.showReaderToc) }
    }
}

private fun MainViewModel.closeBook() {
    val book = _state.value.currentBook ?: return
    val currentPage = _state.value.currentPageInBook
    // Save the final reading progress before closing.
    readingProgressRepository.saveProgress(book.filePath, currentPage)
    // Atomically reset all reader-specific state to prevent UI glitches during transition.
    _state.update {
        it.copy(
            currentBook = null,
            currentPageInBook = 0,
            currentChapterIndex = 0,
            totalPagesInBook = 0,
            showReaderToc = false
        )
    }
}

private fun MainViewModel.changeChapter(delta: Int) {
    val book = _state.value.currentBook ?: return
    val currentChapterIndex = _state.value.currentChapterIndex
    val newChapterIndex = (currentChapterIndex + delta).coerceIn(0, book.chapters.lastIndex)

    if (currentChapterIndex != newChapterIndex) {
        var pageCounter = 0
        for (i in 0 until newChapterIndex) {
            pageCounter += book.chapters[i].imageResources.size
        }
        val newPage = pageCounter + 1

        // **FIX:** Directly and atomically update the state here instead of firing another event.
        // This prevents the UI from momentarily having an inconsistent state.
        readingProgressRepository.saveProgress(book.filePath, newPage)
        _state.update {
            it.copy(
                currentPageInBook = newPage,
                currentChapterIndex = newChapterIndex
            )
        }
    }
}


internal fun MainViewModel.scanForLibraryBooks(pathOverride: String? = null) {
    viewModelScope.launch(Dispatchers.IO) {
        _state.update { it.copy(isLibraryLoading = true) }

        // Use the configured scan paths, or fall back to the default output path if none are set.
        val pathsToScan = _state.value.libraryScanPaths.ifEmpty {
            setOfNotNull(_state.value.outputPath)
        }

        if (pathsToScan.isEmpty()) {
            withContext(Dispatchers.Main) { _state.update { it.copy(isLibraryLoading = false, libraryBooks = emptyList()) } }
            return@launch
        }

        val allBooks = mutableSetOf<com.mangacombiner.service.Book>()

        for (path in pathsToScan) {
            val epubFiles = File(path).walk()
                .maxDepth(3) // Limit scan depth for performance
                .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
                .toList()

            val booksInPath = epubFiles.map { file ->
                async { epubReaderService.parseEpub(file.absolutePath) }
            }.awaitAll().filterNotNull()
            allBooks.addAll(booksInPath)
        }

        withContext(Dispatchers.Main) {
            _state.update { it.copy(isLibraryLoading = false, libraryBooks = allBooks.sortedBy { book -> book.title }) }
        }
    }
}

internal suspend fun MainViewModel.openBook(bookPath: String) {
    val book = _state.value.libraryBooks.find { it.filePath == bookPath }
        ?: epubReaderService.parseEpub(bookPath)

    if (book != null) {
        val lastPage = readingProgressRepository.getProgress(book.filePath)
        val totalPages = book.chapters.sumOf { it.imageResources.size }

        withContext(Dispatchers.Main) {
            _state.update {
                it.copy(
                    currentBook = book,
                    currentPageInBook = lastPage.coerceAtLeast(1),
                    totalPagesInBook = totalPages,
                    readerImageScale = 1.0f
                )
            }
        }
    }
}
