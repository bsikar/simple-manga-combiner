package com.mangacombiner.data

import android.content.Context
import androidx.core.content.edit

actual class SafeOverrideRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("safe_overrides", Context.MODE_PRIVATE)
    private val SAFE_PATHS = "safe_paths"

    actual fun setSafe(bookPath: String, isSafe: Boolean) {
        val currentPaths = loadSafePaths().toMutableSet()
        if (isSafe) {
            currentPaths.add(bookPath)
        } else {
            currentPaths.remove(bookPath)
        }
        prefs.edit(commit = true) {
            putStringSet(SAFE_PATHS, currentPaths)
        }
    }

    actual fun loadSafePaths(): Set<String> {
        return prefs.getStringSet(SAFE_PATHS, emptySet()) ?: emptySet()
    }
}
