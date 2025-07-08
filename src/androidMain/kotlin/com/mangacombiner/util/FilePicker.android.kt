package com.mangacombiner.util

actual fun showFilePicker(onFileSelected: (path: String?) -> Unit) {
    // This requires launching an ActivityResultLauncher from MainActivity.
    println("File picker not fully implemented for Android.")
    onFileSelected(null)
}
