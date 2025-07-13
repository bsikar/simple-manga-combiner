package com.mangacombiner.service

import com.mangacombiner.model.SearchResult
import com.mangacombiner.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class ScraperService {

    suspend fun findChapterUrlsAndTitles(client: HttpClient, seriesUrl: String, userAgent: String): List<Pair<String, String>> {
        Logger.logDebug { "Scraping series page for chapter URLs and titles: $seriesUrl" }
        val baseUrl = URI(seriesUrl).let { "${it.scheme}://${it.host}" }
        try {
            val response: String = client.get(seriesUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Referrer, baseUrl)
            }.body()
            val soup = Jsoup.parse(response, seriesUrl)
            val chapterLinks = soup.select("li.wp-manga-chapter a")

            if (chapterLinks.isEmpty()) {
                Logger.logDebug { "No chapters found using selector 'li.wp-manga-chapter a'." }
                return emptyList()
            }
            return chapterLinks.map { it.absUrl("href") to it.text().trim() }.reversed()
        } catch (e: Exception) {
            when (e) {
                is ClientRequestException, is IOException -> {
                    throw NetworkException("Failed to fetch chapters from $seriesUrl: ${e.message}", e)
                }
                else -> throw e
            }
        }
    }

    suspend fun search(client: HttpClient, query: String, userAgent: String): List<SearchResult> {
        val searchUrl = "https://www.mangaread.org/?s=${URLEncoder.encode(query, "UTF-8")}&post_type=wp-manga"
        Logger.logDebug { "Scraping search results from: $searchUrl" }
        try {
            val response: String = client.get(searchUrl) {
                header(HttpHeaders.UserAgent, userAgent)
            }.body()
            val soup = Jsoup.parse(response, searchUrl)
            val resultElements = soup.select("div.c-tabs-item__content")

            if (resultElements.isEmpty()) {
                Logger.logDebug { "No search results found for query '$query'." }
                return emptyList()
            }

            return resultElements.mapNotNull { element ->
                val titleElement = element.selectFirst(".tab-summary .post-title a")
                val imageElement = element.selectFirst(".tab-thumb a img")

                val title = titleElement?.text()
                val url = titleElement?.absUrl("href")
                val thumbnailUrl = imageElement?.absUrl("src")

                if (title != null && url != null && thumbnailUrl != null) {
                    SearchResult(title, url, thumbnailUrl)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            when (e) {
                is ClientRequestException, is IOException -> {
                    Logger.logError("Network/Server error during search for '$query'", e)
                    throw NetworkException("Failed to perform search: ${e.message}", e)
                }
                else -> throw e
            }
        }
    }

    suspend fun findImageUrls(client: HttpClient, chapterUrl: String, userAgent: String, referer: String): List<String> {
        Logger.logDebug { "Scraping chapter page for image URLs: $chapterUrl" }
        try {
            val response: String = client.get(chapterUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                // Set the series page as the referer when accessing a chapter page
                header(HttpHeaders.Referrer, referer)
            }.body()
            val soup = Jsoup.parse(response, chapterUrl)
            val imageTags = soup.select("img.wp-manga-chapter-img")

            if (imageTags.isEmpty()) {
                Logger.logDebug { "No images found using selector 'img.wp-manga-chapter-img'." }
                return emptyList()
            }
            return imageTags.mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
        } catch (e: Exception) {
            when (e) {
                is ClientRequestException, is IOException -> {
                    throw NetworkException("Failed to find image URLs from $chapterUrl: ${e.message}", e)
                }
                else -> throw e
            }
        }
    }
}
