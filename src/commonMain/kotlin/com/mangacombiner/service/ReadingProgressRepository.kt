package com.mangacombiner.service

expect class ReadingProgressRepository {
    fun saveProgress(bookPath: String, page: Int)
    fun getProgress(bookPath: String): Int
}
