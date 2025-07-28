package com.mangacombiner.ui.viewmodel

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope as androidViewModelScope
import com.mangacombiner.service.Book
import com.mangacombiner.util.AndroidPlatformProvider
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

            val pathsToScan = mainViewModel.state.value.libraryScanPaths.ifEmpty {
                setOfNotNull(mainViewModel.state.value.outputPath)
            }

            for (path in pathsToScan) {
                if (path.isBlank()) continue

                val files = if (path.startsWith("content://")) {
                    DocumentFile.fromTreeUri(context, Uri.parse(path))
                        ?.listFiles()
                        ?.filter { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true }
                        ?.map { it.uri.toString() } ?: emptyList()
                } else {
                    java.io.File(path).walk()
                        .maxDepth(3)
                        .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
                        .map { it.absolutePath }
                        .toList()
                }

                val booksInPath = files.map { filePath ->
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
            val lastPage = mainViewModel.readingProgressRepository.getProgress(book.filePath)
            val totalPages = book.chapters.sumOf { it.imageHrefs.size }

            mainViewModel._state.update {
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
