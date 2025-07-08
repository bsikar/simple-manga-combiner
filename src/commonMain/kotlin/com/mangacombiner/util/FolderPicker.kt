package com.mangacombiner.util

/**
 * We expect each platform to provide a way to show a native folder picker.
 * The function takes a callback to return the selected path string.
 */
expect fun showFolderPicker(onFolderSelected: (path: String?) -> Unit)
