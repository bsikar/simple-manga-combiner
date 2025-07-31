package com.mangacombiner.data

import java.util.prefs.Preferences

actual class NsfwRepository {
    private val prefs = Preferences.userNodeForPackage(this::class.java).node("nsfw_flags")
    private val pathDelimiter = "|||"
    private val NSFW_PATHS = "nsfw_paths"

    actual fun setNsfw(bookPath: String, isNsfw: Boolean) {
        val currentPaths = loadNsfwPaths().toMutableSet()
        if (isNsfw) {
            currentPaths.add(bookPath)
        } else {
            currentPaths.remove(bookPath)
        }
        prefs.put(NSFW_PATHS, currentPaths.joinToString(pathDelimiter))
    }

    actual fun loadNsfwPaths(): Set<String> {
        val savedPaths = prefs.get(NSFW_PATHS, "")
        return if (savedPaths.isBlank()) emptySet() else savedPaths.split(pathDelimiter).toSet()
    }
}
