package com.mangacombiner.scraper

import com.mangacombiner.util.logDebug
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.jsoup.Jsoup

/**
 * A scraper specifically designed for WordPress-based manga sites.
 * It looks for common class names used by manga plugins.
 */
object WPMangaScraper {

    /**
     * Finds all chapter URLs from a given manga series page.
     * @param client The HttpClient to use for the request.
     * @param seriesUrl The URL of the main manga page.
     * @return A list of chapter URLs, sorted from first to last.
     */
    suspend fun findChapterUrls(client: HttpClient, seriesUrl: String): List<String> {
        logDebug { "Scraping series page for chapter URLs: $seriesUrl" }
        return try {
            val response: String = client.get(seriesUrl).body()
            val soup = Jsoup.parse(response, seriesUrl)
            // Select links within list items that have the 'wp-manga-chapter' class
            val chapterLinks = soup.select("li.wp-manga-chapter a")

            if (chapterLinks.isEmpty()) {
                logDebug { "No chapters found using selector 'li.wp-manga-chapter a'." }
                return emptyList()
            }

            // URLs are typically listed newest to oldest, so we reverse them for correct order.
            chapterLinks.map { it.absUrl("href") }.reversed()
        } catch (e: Exception) {
            println("Error finding chapter URLs from $seriesUrl: ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds all image URLs from a given chapter page.
     * @param client The HttpClient to use for the request.
     * @param chapterUrl The URL of the chapter page.
     * @return A list of image URLs in the correct order.
     */
    suspend fun findImageUrls(client: HttpClient, chapterUrl: String): List<String> {
        logDebug { "Scraping chapter page for image URLs: $chapterUrl" }
        return try {
            val response: String = client.get(chapterUrl).body()
            val soup = Jsoup.parse(response, chapterUrl)
            // Select images with the 'wp-manga-chapter-img' class
            val imageTags = soup.select("img.wp-manga-chapter-img")

            if (imageTags.isEmpty()) {
                logDebug { "No images found using selector 'img.wp-manga-chapter-img'." }
                return emptyList()
            }

            imageTags.mapNotNull { it.absUrl("src").takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            println("Error finding image URLs from $chapterUrl: ${e.message}")
            emptyList()
        }
    }
}
