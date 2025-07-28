package com.mangacombiner.service

import android.content.Context

actual class ReadingProgressRepository(context: Context) {
    private val prefs = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    actual fun saveProgress(bookPath: String, page: Int) = prefs.edit().putInt(bookPath.hashCode().toString(), page).apply()
    actual fun getProgress(bookPath: String): Int = prefs.getInt(bookPath.hashCode().toString(), 0)
}
