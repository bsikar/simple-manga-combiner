package com.mangacombiner.service

import com.mangacombiner.model.ScrapedSeriesCache
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ScrapeCacheService(private val platformProvider: PlatformProvider) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val cacheFile: File
        get() = File(platformProvider.getTmpDir(), "scrape_cache.json")

    fun saveCache(cache: ScrapedSeriesCache) {
        try {
            val jsonString = json.encodeToString(cache)
            cacheFile.writeText(jsonString)
            Logger.logInfo("Saved ${cache.series.size} series to scrape cache at ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Logger.logError("Failed to save scrape cache.", e)
        }
    }

    fun loadCache(): ScrapedSeriesCache? {
        if (!cacheFile.exists()) {
            Logger.logDebug { "Scrape cache file does not exist." }
            return null
        }
        return try {
            val jsonString = cacheFile.readText()
            if (jsonString.isBlank()) return null
            val cache = json.decodeFromString<ScrapedSeriesCache>(jsonString)
            Logger.logInfo("Loaded ${cache.series.size} series from scrape cache.")
            cache
        } catch (e: Exception) {
            Logger.logError("Failed to load or parse scrape cache.", e)
            null
        }
    }
}
