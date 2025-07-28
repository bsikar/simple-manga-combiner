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
        is Event.Library.ScanCustomFolder -> requestCustomLibraryFolder()
        is Event.Library.OpenBook -> viewModelScope.launch { openBook(event.bookPath) }
        is Event.Library.CloseBook -> closeBook()
        is Event.Library.NextChapter -> changeChapter(1)
        is Event.Library.PreviousChapter -> changeChapter(-1)
        is Event.Library.ChangeReaderTheme -> _state.update { it.copy(readerTheme = event.theme) }
        is Event.Library.GoToPage -> {
            _state.value.currentBook?.let { book ->
                readingProgressRepository.saveProgress(book.filePath, event.page)
            }
            _state.update { it.copy(currentPageInBook = event.page) }
        }
        is Event.Library.ChangeImageScale -> _state.update { it.copy(readerImageScale = event.scale) }
        is Event.Library.ResetImageScale -> _state.update { it.copy(readerImageScale = 1.0f) }
        is Event.Library.UpdateProgress -> {
            _state.value.currentBook?.let { book ->
                readingProgressRepository.saveProgress(book.filePath, event.currentPage)
            }
            _state.update { it.copy(currentPageInBook = event.currentPage, currentChapterIndex = event.currentChapterIndex) }
        }
    }
}

private fun MainViewModel.closeBook() {
    val book = _state.value.currentBook ?: return
    val currentPage = _state.value.currentPageInBook
    readingProgressRepository.saveProgress(book.filePath, currentPage)
    _state.update { it.copy(currentBook = null) }
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
        onEvent(Event.Library.GoToPage(pageCounter + 1))
    }
}

private fun MainViewModel.requestCustomLibraryFolder() {
    viewModelScope.launch {
        _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.LIBRARY_SCAN))
    }
}

internal fun MainViewModel.scanForLibraryBooks(pathOverride: String? = null) {
    viewModelScope.launch(Dispatchers.IO) {
        _state.update { it.copy(isLibraryLoading = true) }
        val scanPath = pathOverride ?: _state.value.outputPath
        if (scanPath.isBlank()) {
            withContext(Dispatchers.Main) { _state.update { it.copy(isLibraryLoading = false, libraryBooks = emptyList()) } }
            return@launch
        }

        val epubFiles = File(scanPath).walk()
            .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
            .toList()

        val books = epubFiles.map { file ->
            async { epubReaderService.parseEpub(file.absolutePath) }
        }.awaitAll()
            .filterNotNull()
            .sortedBy { it.title }

        withContext(Dispatchers.Main) {
            _state.update { it.copy(isLibraryLoading = false, libraryBooks = books) }
        }
    }
}

private suspend fun MainViewModel.openBook(bookPath: String) {
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
