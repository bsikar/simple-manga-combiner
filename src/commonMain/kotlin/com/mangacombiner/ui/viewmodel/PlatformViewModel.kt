package com.mangacombiner.ui.viewmodel

import kotlinx.coroutines.CoroutineScope

/**
 * An expect class for a platform-specific ViewModel that provides
 * a lifecycle-aware CoroutineScope and platform-specific library functions.
 */
expect open class PlatformViewModel() {
    val viewModelScope: CoroutineScope

    /**
     * Platform-specific implementation for scanning library folders.
     */
    fun scanForLibraryBooks()

    /**
     * Platform-specific implementation for opening a book.
     */
    suspend fun openBook(bookPath: String)

    /**
     * Platform-specific implementation for closing a book.
     */
    fun closeBook()
}
