package com.mangacombiner.util

/**
 * We expect each platform to provide a way to show a native file picker.
 * The function takes a callback to return the selected path string.
 */
expect fun showFilePicker(onFileSelected: (path: String?) -> Unit)
