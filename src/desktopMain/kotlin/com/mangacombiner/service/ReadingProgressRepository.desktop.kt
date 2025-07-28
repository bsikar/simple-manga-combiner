package com.mangacombiner.service

import java.util.prefs.Preferences

actual class ReadingProgressRepository {
    private val prefs = Preferences.userNodeForPackage(this::class.java).node("reading_progress")
    actual fun saveProgress(bookPath: String, page: Int) = prefs.putInt(bookPath.hashCode().toString(), page)
    actual fun getProgress(bookPath: String): Int = prefs.getInt(bookPath.hashCode().toString(), 0)
}
