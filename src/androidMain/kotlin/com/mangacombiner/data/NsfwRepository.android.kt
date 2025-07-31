package com.mangacombiner.data

import android.content.Context

actual class NsfwRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("nsfw_flags", Context.MODE_PRIVATE)
    private val NSFW_PATHS = "nsfw_paths"

    actual fun setNsfw(bookPath: String, isNsfw: Boolean) {
        val currentPaths = loadNsfwPaths().toMutableSet()
        if (isNsfw) {
            currentPaths.add(bookPath)
        } else {
            currentPaths.remove(bookPath)
        }
        prefs.edit().putStringSet(NSFW_PATHS, currentPaths).apply()
    }

    actual fun loadNsfwPaths(): Set<String> {
        return prefs.getStringSet(NSFW_PATHS, emptySet()) ?: emptySet()
    }
}
