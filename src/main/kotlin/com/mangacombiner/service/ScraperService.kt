package com.mangacombiner.service

import com.mangacombiner.util.logDebug
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.io.IOException

@Service
class ScraperService {

    /**
     * Scrapes a series page to find all chapter URLs.
     * @return A list of chapter URLs, sorted from oldest to newest.
     */
    suspend fun findChapterUrls(client: HttpClient, seriesUrl: String): List<String> {
        // This function now uses the new function to maintain compatibility.
        return findChapterUrlsAndTitles(client, seriesUrl).map { it.first }
    }

    /**
     * Scrapes a series page to find all chapter URLs and their corresponding titles.
     * @return A list of pairs, where each pair contains the chapter URL and its title, sorted from oldest to newest.
     */
    suspend fun findChapterUrlsAndTitles(client: HttpClient, seriesUrl: String): List<Pair<String, String>> {
        logDebug { "Scraping series page for chapter URLs and titles: $seriesUrl" }
        return try {
            val response: String = client.get(seriesUrl).body()
            val soup = Jsoup.parse(response, seriesUrl)
            val chapterLinks = soup.select("li.wp-manga-chapter a")

            if (chapterLinks.isEmpty()) {
                logDebug { "No chapters found using selector 'li.wp-manga-chapter a'." }
                return emptyList()
            }
            // Map to a Pair of (URL, Title) and reverse for correct order.
            chapterLinks.map { it.absUrl("href") to it.text().trim() }.reversed()
        } catch (e: ClientRequestException) {
            println("HTTP Error finding chapter URLs from $seriesUrl: ${e.response.status}")
            emptyList()
        } catch (e: IOException) {
            println("IO Error finding chapter URLs from $seriesUrl: ${e.message}")
            emptyList()
        }
    }

    /**
     * Scrapes the series page to find the last update time.
     * @return The text content of the last update element, or null if not found.
     */
    suspend fun findLastUpdateTime(client: HttpClient, seriesUrl: String): String? {
        logDebug { "Scraping series page for last update time: $seriesUrl" }
        return try {
            val response: String = client.get(seriesUrl).body()
            val soup = Jsoup.parse(response, seriesUrl)
            // This selector is common for WP-Manga themes.
            soup.select(".post-status .post-on, .post-status .last-updated time, .rate-title.updated")
                .first()?.text()?.trim()
        } catch (e: ClientRequestException) {
            println("Could not determine last update time from $seriesUrl due to a network error: ${e.response.status}")
            null
        } catch (e: IOException) {
            println("Could not determine last update time from $seriesUrl due to an IO error: ${e.message}")
            null
        }
    }

    /**
     * Scrapes a chapter page to find all image URLs.
     * @return A list of image URLs.
     */
    suspend fun findImageUrls(client: HttpClient, chapterUrl: String): List<String> {
        logDebug { "Scraping chapter page for image URLs: $chapterUrl" }
        return try {
            val response: String = client.get(chapterUrl).body()
            val soup = Jsoup.parse(response, chapterUrl)
            val imageTags = soup.select("img.wp-manga-chapter-img")

            if (imageTags.isEmpty()) {
                logDebug { "No images found using selector 'img.wp-manga-chapter-img'." }
                return emptyList()
            }
            imageTags.mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
        } catch (e: ClientRequestException) {
            println("HTTP Error finding image URLs from $chapterUrl: ${e.response.status}")
            emptyList()
        } catch (e: IOException) {
            println("IO Error finding image URLs from $chapterUrl: ${e.message}")
            emptyList()
        }
    }
}
