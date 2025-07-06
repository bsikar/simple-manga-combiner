package com.mangacombiner.service

import com.mangacombiner.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.io.IOException

@Service
class ScraperService {

    suspend fun findChapterUrls(client: HttpClient, seriesUrl: String): List<String> {
        return findChapterUrlsAndTitles(client, seriesUrl).map { it.first }
    }

    suspend fun findChapterUrlsAndTitles(client: HttpClient, seriesUrl: String): List<Pair<String, String>> {
        Logger.logDebug { "Scraping series page for chapter URLs and titles: $seriesUrl" }
        return try {
            val response: String = client.get(seriesUrl).body()
            val soup = Jsoup.parse(response, seriesUrl)
            val chapterLinks = soup.select("li.wp-manga-chapter a")

            if (chapterLinks.isEmpty()) {
                Logger.logDebug { "No chapters found using selector 'li.wp-manga-chapter a'." }
                return emptyList()
            }
            chapterLinks.map { it.absUrl("href") to it.text().trim() }.reversed()
        } catch (e: ClientRequestException) {
            Logger.logError("HTTP Error finding chapter URLs from $seriesUrl: ${e.response.status}")
            emptyList()
        } catch (e: IOException) {
            Logger.logError("IO Error finding chapter URLs from $seriesUrl: ${e.message}")
            emptyList()
        }
    }

    suspend fun findLastUpdateTime(client: HttpClient, seriesUrl: String): String? {
        Logger.logDebug { "Scraping series page for last update time: $seriesUrl" }
        return try {
            val response: String = client.get(seriesUrl).body()
            val soup = Jsoup.parse(response, seriesUrl)
            soup.select(".post-status .post-on, .post-status .last-updated time, .rate-title.updated")
                .first()?.text()?.trim()
        } catch (e: ClientRequestException) {
            Logger.logError(
                "Could not determine last update time from $seriesUrl due to a network error: ${e.response.status}"
            )
            null
        } catch (e: IOException) {
            Logger.logError("Could not determine last update time from $seriesUrl due to an IO error: ${e.message}")
            null
        }
    }

    suspend fun findImageUrls(client: HttpClient, chapterUrl: String): List<String> {
        Logger.logDebug { "Scraping chapter page for image URLs: $chapterUrl" }
        return try {
            val response: String = client.get(chapterUrl).body()
            val soup = Jsoup.parse(response, chapterUrl)
            val imageTags = soup.select("img.wp-manga-chapter-img")

            if (imageTags.isEmpty()) {
                Logger.logDebug { "No images found using selector 'img.wp-manga-chapter-img'." }
                return emptyList()
            }
            imageTags.mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
        } catch (e: ClientRequestException) {
            Logger.logError("HTTP Error finding image URLs from $chapterUrl: ${e.response.status}")
            emptyList()
        } catch (e: IOException) {
            Logger.logError("IO Error finding image URLs from $chapterUrl: ${e.message}")
            emptyList()
        }
    }
}
