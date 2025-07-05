package com.mangacombiner.service

import com.mangacombiner.util.logDebug
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class ScraperService {

    suspend fun findChapterUrls(client: HttpClient, seriesUrl: String): List<String> {
        logDebug { "Scraping series page for chapter URLs: $seriesUrl" }
        return try {
            val response: String = client.get(seriesUrl).body()
            val soup = Jsoup.parse(response, seriesUrl)
            val chapterLinks = soup.select("li.wp-manga-chapter a")

            if (chapterLinks.isEmpty()) {
                logDebug { "No chapters found using selector 'li.wp-manga-chapter a'." }
                return emptyList()
            }
            // URLs are listed newest to oldest, so reverse for correct order.
            chapterLinks.map { it.absUrl("href") }.reversed()
        } catch (e: Exception) {
            println("Error finding chapter URLs from $seriesUrl: ${e.message}")
            emptyList()
        }
    }

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
        } catch (e: Exception) {
            println("Error finding image URLs from $chapterUrl: ${e.message}")
            emptyList()
        }
    }
}
