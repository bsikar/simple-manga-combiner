package com.mangacombiner.util

import java.awt.FileDialog
import java.awt.Frame

actual fun showFilePicker(onFileSelected: (path: String?) -> Unit) {
    // Ensure this is set to false to select files, not directories
    System.setProperty("apple.awt.fileDialogForDirectories", "false")
    try {
        val dialog = FileDialog(null as Frame?, "Select EPUB or CBZ File", FileDialog.LOAD).apply {
            // Filter for only .cbz and .epub files
            setFilenameFilter { _, name -> name.endsWith(".cbz", true) || name.endsWith(".epub", true) }
            isVisible = true
        }

        val directory = dialog.directory
        val file = dialog.file

        if (directory != null && file != null) {
            onFileSelected(directory + file)
        } else {
            onFileSelected(null)
        }
    } finally {
        // It's good practice to not leave system properties modified.
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}
