package com.mangacombiner.util

import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboardManager
import android.content.Context

actual class ClipboardManager(private val context: Context) {
    actual fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
        val clip = ClipData.newPlainText("MangaCombinerLogs", text)
        clipboard.setPrimaryClip(clip)
        Logger.logInfo("Logs copied to clipboard.")
    }
}
