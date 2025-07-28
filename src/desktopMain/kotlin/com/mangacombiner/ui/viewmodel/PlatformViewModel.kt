package com.mangacombiner.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop implementation of PlatformViewModel.
 * Provides a CoroutineScope that lives until the app is closed.
 */
actual open class PlatformViewModel {
    actual val viewModelScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainViewModel by lazy { this as MainViewModel }

    /**
     * On desktop, this would be called if the ViewModel had a clear "destroy" event.
     * For this application, the scope lives until the application exits.
     */
    fun onClear() {
        viewModelScope.cancel()
    }

    actual fun scanForLibraryBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            mainViewModel._state.update { it.copy(isLibraryLoading = true) }

            val pathsToScan = mainViewModel.state.value.libraryScanPaths

            if (pathsToScan.isEmpty()) {
                mainViewModel._state.update { it.copy(isLibraryLoading = false, libraryBooks = emptyList()) }
                return@launch
            }

            val allBooks = mutableSetOf<com.mangacombiner.service.Book>()

            for (path in pathsToScan) {
                if (path.isBlank()) continue
                val epubFiles = File(path).walk()
                    .maxDepth(3)
                    .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
                    .toList()

                val booksInPath = epubFiles.map { file ->
                    async { mainViewModel.epubReaderService.parseEpub(file.absolutePath) }
                }.awaitAll().filterNotNull()
                allBooks.addAll(booksInPath)
            }
            mainViewModel._state.update { it.copy(isLibraryLoading = false, libraryBooks = allBooks.sortedBy { book -> book.title }) }
        }
    }

    actual suspend fun openBook(bookPath: String) {
        val book = mainViewModel.state.value.libraryBooks.find { it.filePath == bookPath }
            ?: mainViewModel.epubReaderService.parseEpub(bookPath)

        if (book != null) {
            val lastPage = mainViewModel.readingProgressRepository.getProgress(book.filePath)
            val totalPages = book.chapters.sumOf { it.imageHrefs.size.coerceAtLeast(if (it.textContent != null) 1 else 0) }

            mainViewModel._state.update {
                it.copy(
                    currentBook = book,
                    currentPageInBook = lastPage.coerceAtLeast(1),
                    totalPagesInBook = totalPages,
                    readerFontSize = 16.0f
                )
            }
        }
    }

    actual fun closeBook() {
        val book = mainViewModel.state.value.currentBook ?: return
        val currentPage = mainViewModel.state.value.currentPageInBook
        mainViewModel.readingProgressRepository.saveProgress(book.filePath, currentPage)
        mainViewModel._state.update {
            it.copy(
                currentBook = null,
                currentPageInBook = 0,
                currentChapterIndex = 0,
                totalPagesInBook = 0,
                showReaderToc = false
            )
        }
    }
}
