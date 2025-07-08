package com.mangacombiner.util

import javax.swing.JFileChooser

actual fun showFolderPicker(onFolderSelected: (path: String?) -> Unit) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Select Output Folder"
    }
    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        onFolderSelected(chooser.selectedFile.absolutePath)
    } else {
        onFolderSelected(null)
    }
}
