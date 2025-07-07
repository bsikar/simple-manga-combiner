package com.mangacombiner.util

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual class ClipboardManager {
    actual fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, selection)
        Logger.logInfo("Logs copied to clipboard.")
    }
}
