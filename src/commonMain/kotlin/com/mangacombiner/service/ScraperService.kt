package com.mangacombiner.service

import com.mangacombiner.model.SearchResult
import com.mangacombiner.util.Logger
import com.mangacombiner.util.toSlug
import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import kotlin.random.Random

class ScraperService {

    /**
     * Fetches detailed information for a manga series, including metadata and chapter list.
     */
    suspend fun fetchSeriesDetails(client: HttpClient, seriesUrl: String, userAgent: String): Pair<SeriesMetadata, List<Pair<String, String>>> {
        val host = URI(seriesUrl).host.replace("www.", "")
        Logger.logDebug { "Fetching series details from host: $host" }

        val response: String = client.get(seriesUrl) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Referrer, URI(seriesUrl).let { "${it.scheme}://${it.host}" })
        }.body()
        val soup = Jsoup.parse(response, seriesUrl)

        val seriesMetadata = when (host) {
            "manhwaus.net" -> {
                val title = soup.selectFirst("div.post-title h1")?.text()?.trim() ?: "Unknown Title"
                val coverImageUrl = soup.selectFirst("div.summary_image img")?.absUrl("src")
                val description = soup.selectFirst("div.summary__content p, div.entry-content p")?.text()?.trim()


                var authors: List<String>? = null
                var artists: List<String>? = null
                var genres: List<String>? = null
                var type: String? = null
                var status: String? = null

                soup.select("div.post-content_item").forEach { item ->
                    val heading = item.selectFirst("b")?.text()?.trim()
                    when (heading) {
                        "Author(s):" -> authors = item.select("a").map { it.text().trim() }
                        "Artist(s):" -> artists = item.select("a").map { it.text().trim() }
                        "Genres:" -> genres = item.select("a").map { it.text().trim() }
                    }
                }

                soup.select("div.minfo").forEach { element ->
                    val text = element.text()
                    if (text.startsWith("Status")) {
                        status = text.substringAfter("Status").trim()
                    } else if (text.startsWith("Type")) {
                        type = text.substringAfter("Type").trim()
                    }
                }

                SeriesMetadata(
                    title = title,
                    coverImageUrl = coverImageUrl,
                    description = description,
                    authors = authors?.takeIf { it.isNotEmpty() },
                    artists = artists?.takeIf { it.isNotEmpty() },
                    genres = genres?.takeIf { it.isNotEmpty() },
                    type = type,
                    status = status,
                    release = null
                )
            }
            "mangaread.org" -> {
                val title = soup.selectFirst("div.post-title h1")?.text()?.trim() ?: "Unknown Title"
                val coverImageUrl = soup.selectFirst("div.summary_image img")?.absUrl("src")
                val description = soup.selectFirst("div.summary__content")?.text()?.trim()

                var authors: List<String>? = null
                var artists: List<String>? = null
                var genres: List<String>? = null
                var type: String? = null
                var status: String? = null
                var release: String? = null

                soup.select("div.post-content_item").forEach { item ->
                    val heading = item.selectFirst("h5")?.text()?.trim()
                    val content = item.selectFirst("div.summary-content")

                    when (heading) {
                        "Author(s)" -> authors = content?.select("a")?.map { it.text().trim() }
                        "Artist(s)" -> artists = content?.select("a")?.map { it.text().trim() }
                        "Genre(s)" -> genres = content?.select("a")?.map { it.text().trim() }
                        "Type" -> type = content?.text()?.trim()
                        "Release" -> release = content?.text()?.trim()
                        "Status" -> status = content?.text()?.trim()
                    }
                }

                SeriesMetadata(
                    title = title,
                    coverImageUrl = coverImageUrl,
                    description = description,
                    authors = authors?.takeIf { it.isNotEmpty() },
                    artists = artists?.takeIf { it.isNotEmpty() },
                    genres = genres?.takeIf { it.isNotEmpty() },
                    type = type,
                    status = status,
                    release = release
                )
            }
            else -> {
                Logger.logError("Unsupported website: $host")
                throw IllegalArgumentException("Unsupported website: $host")
            }
        }
        val chapters = findChapterUrlsAndTitles(soup, seriesUrl)
        return seriesMetadata to chapters
    }

    private fun findChapterUrlsAndTitles(soup: org.jsoup.nodes.Document, seriesUrl: String): List<Pair<String, String>> {
        val host = URI(seriesUrl).host.replace("www.", "")
        return when(host) {
            "manhwaus.net" -> soup.select("ul.row-content-chapter li.a-h a.chapter-name").map { it.absUrl("href") to it.text().trim() }.reversed()
            "mangaread.org" -> soup.select("li.wp-manga-chapter a").map { it.absUrl("href") to it.text().trim() }.reversed()
            else -> emptyList()
        }
    }

    internal suspend fun findChapterUrlsAndTitles(client: HttpClient, seriesUrl: String, userAgent: String): List<Pair<String, String>> {
        Logger.logDebug { "Scraping series page for chapter URLs and titles: $seriesUrl" }
        try {
            val response: String = client.get(seriesUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Referrer, URI(seriesUrl).let { "${it.scheme}://${it.host}" })
            }.body()
            val soup = Jsoup.parse(response, seriesUrl)
            return findChapterUrlsAndTitles(soup, seriesUrl)
        } catch (e: Exception) {
            when (e) {
                is ClientRequestException, is IOException -> {
                    throw NetworkException("Failed to fetch chapters from $seriesUrl: ${e.message}", e)
                }
                else -> throw e
            }
        }
    }

    suspend fun search(client: HttpClient, query: String, userAgent: String, source: String): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val searchUrl = when (source) {
            "manhwaus.net" -> "https://manhwaus.net/search/?s=$encodedQuery"
            "mangaread.org" -> "https://www.mangaread.org/?s=$encodedQuery&post_type=wp-manga"
            else -> throw IllegalArgumentException("Unsupported search source: $source")
        }

        Logger.logDebug { "Scraping search results from: $searchUrl" }
        try {
            val response: String = client.get(searchUrl) {
                header(HttpHeaders.UserAgent, userAgent)
            }.body()
            val soup = Jsoup.parse(response, searchUrl)

            val resultElements = when (source) {
                "manhwaus.net" -> soup.select("div.page-item-detail")
                "mangaread.org" -> soup.select("div.c-tabs-item__content")
                else -> emptyList()
            }

            if (resultElements.isEmpty()) {
                Logger.logDebug { "No search results found for query '$query' on $source." }
                return emptyList()
            }

            return resultElements.mapNotNull { element ->
                val titleElement = element.selectFirst("div.item-summary h3 a")
                val imageElement = element.selectFirst("div.item-thumb a img")

                val title = titleElement?.text()?.trim()
                val url = titleElement?.absUrl("href")
                val thumbnailUrl = imageElement?.absUrl("data-src") ?: imageElement?.absUrl("src")

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
        val host = URI(chapterUrl).host.replace("www.", "")
        Logger.logDebug { "Scraping chapter page for image URLs: $chapterUrl from host $host" }

        try {
            val response: String = client.get(chapterUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Referrer, referer)
            }.body()
            val soup = Jsoup.parse(response, chapterUrl)
            return when (host) {
                "manhwaus.net" -> soup.select("div.read-content img.loading").mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
                "mangaread.org" -> soup.select("img.wp-manga-chapter-img").mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
                else -> emptyList()
            }
        } catch (e: Exception) {
            when (e) {
                is ClientRequestException, is IOException -> {
                    throw NetworkException("Failed to find image URLs from $chapterUrl: ${e.message}", e)
                }
                else -> throw e
            }
        }
    }

    suspend fun findAllSeriesUrls(client: HttpClient, startUrl: String, userAgent: String): List<SearchResult> {
        val allResults = mutableSetOf<SearchResult>()
        val baseUrl = URI(startUrl).let { "${it.scheme}://${it.host}" }
        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
        var page = 1

        while (true) {
            Logger.logInfo("Scraping page for series: $page")
            try {
                val payload = Parameters.build {
                    append("action", "madara_load_more")
                    append("page", page.toString())
                    append("template", "madara-core/content/content-archive")
                    append("vars[paged]", page.toString())
                    append("vars[orderby]", "meta_value_num")
                    append("vars[template]", "archive")
                    append("vars[sidebar]", "right")
                    append("vars[post_type]", "wp-manga")
                    append("vars[post_status]", "publish")
                    append("vars[meta_key]", "_latest_update")
                    append("vars[order]", "desc")
                    append("vars[manga_archives_item_layout]", "default")
                }

                val response: String = client.post(ajaxUrl) {
                    setBody(FormDataContent(payload))
                    header(HttpHeaders.UserAgent, userAgent)
                    header(HttpHeaders.Referrer, startUrl)
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=UTF-8")
                    header("X-Requested-With", "XMLHttpRequest")
                }.body()

                if (response.isBlank()) {
                    Logger.logInfo("No more series found. Stopping scrape.")
                    break
                }

                val soup = Jsoup.parse(response, baseUrl)
                val seriesElements = soup.select("div.page-item-detail a[href*='/manga/'], div.page-item-detail a[href*='/webtoon/']")

                if (seriesElements.isEmpty()) {
                    Logger.logInfo("No more series found on this page. Stopping scrape.")
                    break
                }

                val resultsOnPage = seriesElements.mapNotNull { element ->
                    val url = element.absUrl("href")
                    val title = element.attr("title")

                    if (title.isNotBlank() && url.isNotBlank()) {
                        SearchResult(title = title, url = url, thumbnailUrl = "")
                    } else {
                        null
                    }
                }.toSet()

                if (resultsOnPage.isNotEmpty()) {
                    resultsOnPage.forEach {
                        Logger.logInfo("  - Found: ${it.title}")
                    }
                }

                allResults.addAll(resultsOnPage)
                page++
                delay(Random.nextLong(250, 750)) // Polite delay

            } catch (e: Exception) {
                when (e) {
                    is ClientRequestException, is IOException -> Logger.logError("Network/Server error while scraping page '$page'", e)
                    else -> Logger.logError("An unexpected error occurred on page $page", e)
                }
                break
            }
        }
        val uniqueResults = allResults.distinctBy { it.url }
        Logger.logInfo("Total unique series found across all pages: ${uniqueResults.size}")
        return uniqueResults.toList()
    }
}
