package com.mangacombiner.util

actual fun showFolderPicker(onFolderSelected: (path: String?) -> Unit) {
    // This requires launching an ActivityResultLauncher from MainActivity.
    println("Folder picker not fully implemented for Android.")
    onFolderSelected(null)
}
