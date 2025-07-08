package com.mangacombiner.util

import java.awt.FileDialog
import java.awt.Frame

actual fun showFolderPicker(onFolderSelected: (path: String?) -> Unit) {
    // This system property is specific to macOS to make the file dialog select directories.
    System.setProperty("apple.awt.fileDialogForDirectories", "true")

    try {
        val dialog = FileDialog(null as Frame?, "Select Output Folder", FileDialog.LOAD)
        dialog.isVisible = true

        val directory = dialog.directory
        val file = dialog.file

        if (directory != null && file != null) {
            onFolderSelected(directory + file)
        } else {
            onFolderSelected(null)
        }
    } finally {
        // Reset the system property to avoid side effects for other Java apps.
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}
