package com.mangacombiner.ui.viewmodel

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope as androidViewModelScope
import com.mangacombiner.service.Book
import com.mangacombiner.util.AndroidPlatformProvider
import com.mangacombiner.util.titlecase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Android implementation of PlatformViewModel.
 * Inherits from AndroidX ViewModel and provides its lifecycle-aware viewModelScope.
 */
actual open class PlatformViewModel : ViewModel() {
    actual val viewModelScope: CoroutineScope
        get() = androidViewModelScope

    private val mainViewModel by lazy { this as MainViewModel }

    actual fun scanForLibraryBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            mainViewModel._state.update { it.copy(isLibraryLoading = true) }

            val context = (mainViewModel.platformProvider as AndroidPlatformProvider).context
            val allBooks = mutableSetOf<Book>()

            val pathsToScan = mainViewModel.state.value.libraryScanPaths

            if (pathsToScan.isEmpty()) {
                mainViewModel._state.update { it.copy(isLibraryLoading = false, libraryBooks = emptyList()) }
                return@launch
            }

            for (path in pathsToScan) {
                if (path.isBlank()) continue

                // Get a list of file paths/URIs
                val files = if (path.startsWith("content://")) {
                    DocumentFile.fromTreeUri(context, Uri.parse(path))
                        ?.listFiles()
                        ?.filter { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true }
                        ?.map { it.uri.toString() to (it.name ?: "Unknown") } ?: emptyList()
                } else {
                    java.io.File(path).walk()
                        .maxDepth(3)
                        .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
                        .map { it.absolutePath to it.name }
                        .toList()
                }

                // Create lightweight placeholder Book objects instead of fully parsing them.
                val booksInPath = files.map { (filePath, _) ->
                    async { mainViewModel.epubReaderService.parseEpub(filePath) }
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
            openReaderWithBook(book)
        } else {
            mainViewModel._state.update { it.copy(completionMessage = "Error: Failed to open or parse the selected EPUB file.") }
        }
    }

    /** Helper function to set the state for the reader screen. */
    private suspend fun openReaderWithBook(book: Book) {
        // NEW: Open the persistent file handle for fast image access
        mainViewModel.epubReaderService.openBookForReading(book)

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

    actual fun closeBook() {
        val book = mainViewModel.state.value.currentBook ?: return
        val currentPage = mainViewModel.state.value.currentPageInBook
        mainViewModel.readingProgressRepository.saveProgress(book.filePath, currentPage)

        // Android-specific: Close the persistent file handle to release resources.
        mainViewModel.epubReaderService.closeBookForReading()

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
