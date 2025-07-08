package com.mangacombiner.util

import javax.swing.JFileChooser

/**
 * Desktop implementation of the folder picker using Swing's JFileChooser.
 */
actual fun showFolderPicker(onFolderSelected: (path: String?) -> Unit) {
    // This should be run on the AWT Event Dispatch Thread if called from a non-AWT thread,
    // but for this simple case, direct invocation is often fine.
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
