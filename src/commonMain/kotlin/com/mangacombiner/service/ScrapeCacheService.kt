package com.mangacombiner.service

import com.mangacombiner.model.ScrapedSeries
import com.mangacombiner.model.ScrapedSeriesCache
import com.mangacombiner.model.ScrapedWebsiteCache
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

class ScrapeCacheService(private val platformProvider: PlatformProvider) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val cacheFile: File
        get() = File(platformProvider.getTmpDir(), "scrape_cache.json")

    /**
     * Saves the cache for a specific website. It reads the existing multi-website cache,
     * updates the entry for the given hostname by merging new results, and writes it back.
     */
    fun saveCacheForHost(hostname: String, newCache: ScrapedWebsiteCache) {
        try {
            val existingFullCache = loadCache()?.websites?.toMutableMap() ?: mutableMapOf()
            val existingSiteCache = existingFullCache[hostname]?.series?.toMutableSet() ?: mutableSetOf()

            // Merge new series with existing ones, ensuring uniqueness by URL
            val combinedSeries = (existingSiteCache + newCache.series).distinctBy { it.url }

            val updatedSiteCache = ScrapedWebsiteCache(
                lastUpdated = System.currentTimeMillis(),
                series = combinedSeries
            )

            existingFullCache[hostname] = updatedSiteCache

            val fullCacheToSave = ScrapedSeriesCache(existingFullCache)
            val jsonString = json.encodeToString(fullCacheToSave)
            cacheFile.writeText(jsonString)
            Logger.logInfo("Updated and saved ${combinedSeries.size} total series to scrape cache for host: $hostname")
        } catch (e: Exception) {
            Logger.logError("Failed to save scrape cache for host: $hostname", e)
        }
    }

    /**
     * Loads the entire multi-website scrape cache.
     */
    fun loadCache(): ScrapedSeriesCache? {
        if (!cacheFile.exists()) {
            Logger.logDebug { "Scrape cache file does not exist." }
            return null
        }
        return try {
            val jsonString = cacheFile.readText()
            if (jsonString.isBlank()) return null
            val cache = json.decodeFromString<ScrapedSeriesCache>(jsonString)
            Logger.logInfo("Loaded scrape cache for ${cache.websites.size} website(s).")
            cache
        } catch (e: Exception) {
            Logger.logError("Failed to load or parse scrape cache.", e)
            null
        }
    }

    /**
     * Loads the cache for a specific website from the main cache file.
     */
    fun loadCacheForHost(hostname: String): ScrapedWebsiteCache? {
        return loadCache()?.websites?.get(hostname)
    }
}
