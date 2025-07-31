package com.mangacombiner.data

import java.util.prefs.Preferences

actual class SafeOverrideRepository {
    private val prefs = Preferences.userNodeForPackage(this::class.java).node("safe_overrides")
    private val pathDelimiter = "|||"
    private val SAFE_PATHS = "safe_paths"

    actual fun setSafe(bookPath: String, isSafe: Boolean) {
        val currentPaths = loadSafePaths().toMutableSet()
        if (isSafe) {
            currentPaths.add(bookPath)
        } else {
            currentPaths.remove(bookPath)
        }
        prefs.put(SAFE_PATHS, currentPaths.joinToString(pathDelimiter))
    }

    actual fun loadSafePaths(): Set<String> {
        val savedPaths = prefs.get(SAFE_PATHS, "")
        return if (savedPaths.isBlank()) emptySet() else savedPaths.split(pathDelimiter).toSet()
    }
}
