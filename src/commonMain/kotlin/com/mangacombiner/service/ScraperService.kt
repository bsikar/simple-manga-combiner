package com.mangacombiner.service

import com.mangacombiner.model.AppSettings
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
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import kotlin.random.Random

class ScraperService {

    private fun filterNsfw(series: List<SearchResult>, allowNsfw: Boolean): List<SearchResult> {
        if (allowNsfw) return series
        return series.filter { result ->
            result.genres?.none { genre -> AppSettings.Defaults.nsfwGenres.contains(genre.lowercase()) } ?: true
        }
    }

    private fun filterNsfw(seriesMetadata: SeriesMetadata, allowNsfw: Boolean): SeriesMetadata? {
        if (allowNsfw) return seriesMetadata
        val hasNsfwGenre = seriesMetadata.genres?.any { genre -> AppSettings.Defaults.nsfwGenres.contains(genre.lowercase()) } ?: false
        return if (hasNsfwGenre) null else seriesMetadata
    }

    /**
     * Fetches detailed information for a manga series, including metadata and chapter list.
     */
    suspend fun fetchSeriesDetails(client: HttpClient, seriesUrl: String, userAgent: String, allowNsfw: Boolean): Pair<SeriesMetadata?, List<Pair<String, String>>> {
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

        val filteredMetadata = filterNsfw(seriesMetadata, allowNsfw)
        if (filteredMetadata == null) {
            Logger.logInfo("Filtered out NSFW series: ${seriesMetadata.title}")
            return null to emptyList()
        }

        val chapters = findChapterUrlsAndTitles(soup, seriesUrl)
        return filteredMetadata to chapters
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

    suspend fun search(client: HttpClient, query: String, userAgent: String, source: String, allowNsfw: Boolean): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val searchUrl = when (source) {
            "manhwaus.net" -> "https://manhwaus.net/search/?s=$encodedQuery"
            "mangaread.org" -> "https://mangaread.org/?s=$encodedQuery&post_type=wp-manga"
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

            val results = resultElements.mapNotNull { element ->
                val (titleElement, imageElement, genreElement) = when (source) {
                    "manhwaus.net" -> {
                        val title = element.selectFirst("div.item-summary h3 a")
                        val image = element.selectFirst("div.item-thumb a img")
                        val genres = element.select("div.item-summary .list-genres a").map { it.text() }
                        Triple(title, image, genres)
                    }
                    "mangaread.org" -> {
                        val title = element.selectFirst(".tab-summary .post-title a")
                        val image = element.selectFirst(".tab-thumb a img")
                        val genres = element.select(".tab-meta .genres-content a").map { it.text() }
                        Triple(title, image, genres)
                    }
                    else -> Triple(null, null, null)
                }

                val title = titleElement?.text()?.trim()
                val url = titleElement?.absUrl("href")
                val thumbnailUrl = imageElement?.absUrl("data-src") ?: imageElement?.absUrl("src")

                if (title != null && url != null && thumbnailUrl != null) {
                    SearchResult(title, url, thumbnailUrl, genres = genreElement)
                } else {
                    null
                }
            }
            return filterNsfw(results, allowNsfw)
        } catch (e: Exception) {
            when (e) {
                is ClientRequestException, is IOException -> {
                    throw NetworkException("Failed to search on $source: ${e.message}", e)
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

    private suspend fun scrapeManhwaUs(client: HttpClient, startUrl: String, userAgent: String, allowNsfw: Boolean): List<SearchResult> {
        val allResults = mutableSetOf<SearchResult>()
        var nextPageUrl: String? = startUrl

        while (nextPageUrl != null) {
            Logger.logInfo("Scraping page: $nextPageUrl")

            try {
                val response: String = client.get(nextPageUrl) {
                    header(HttpHeaders.UserAgent, userAgent)
                }.body()
                val soup = Jsoup.parse(response, nextPageUrl)

                val seriesOnPage = soup.select("div.page-item-detail").mapNotNull { element ->
                    val titleElement = element.selectFirst("div.item-summary h3 a")
                    val title = titleElement?.text()?.trim()
                    val url = titleElement?.absUrl("href")
                    val genres = element.select("div.item-summary .list-genres a").map { it.text() }
                    if (title != null && url != null) {
                        SearchResult(title = title, url = url, thumbnailUrl = "", genres = genres)
                    } else {
                        null
                    }
                }

                if (seriesOnPage.isEmpty()) {
                    Logger.logInfo("No more series found on this page. Stopping scrape.")
                    break
                }

                val filteredSeries = filterNsfw(seriesOnPage, allowNsfw)
                filteredSeries.forEach { Logger.logInfo("  - Found: ${it.title}") }
                allResults.addAll(filteredSeries)

                // Correctly find the "next" link by selecting the anchor tag within the list item with class "next"
                val nextLinkElement = soup.selectFirst("li.next a")
                nextPageUrl = nextLinkElement?.absUrl("href")

                if (nextPageUrl == null) {
                    Logger.logInfo("No 'next' page link found. Reached the end of the list.")
                }

                delay(Random.nextLong(250, 750)) // Polite delay

            } catch (e: Exception) {
                Logger.logError("Error scraping page '$nextPageUrl'", e)
                break // Stop on error
            }
        }
        return allResults.distinctBy { it.url }
    }

    private suspend fun scrapeMadara(client: HttpClient, startUrl: String, userAgent: String, allowNsfw: Boolean): List<SearchResult> {
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
                val seriesElements = soup.select("div.page-item-detail")

                if (seriesElements.isEmpty()) {
                    Logger.logInfo("No more series found on this page. Stopping scrape.")
                    break
                }

                val resultsOnPage = seriesElements.mapNotNull { element ->
                    val titleElement = element.selectFirst("a[href*='/manga/'], a[href*='/webtoon/']")
                    val url = titleElement?.absUrl("href")
                    val title = titleElement?.attr("title")
                    val genres = element.select(".genres-content a").map { it.text() }

                    if (title != null && title.isNotBlank() && url != null && url.isNotBlank()) {
                        SearchResult(title = title, url = url, thumbnailUrl = "", genres = genres)
                    } else {
                        null
                    }
                }.toSet()

                val filteredResults = filterNsfw(resultsOnPage.toList(), allowNsfw)

                if (filteredResults.isNotEmpty()) {
                    filteredResults.forEach {
                        Logger.logInfo("  - Found: ${it.title}")
                    }
                }

                allResults.addAll(filteredResults)
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
        return allResults.distinctBy { it.url }
    }

    suspend fun findAllSeriesUrls(client: HttpClient, startUrl: String, userAgent: String, allowNsfw: Boolean): List<SearchResult> {
        val host = try { URI(startUrl).host.replace("www.", "") } catch (e: Exception) { "" }

        val results = when(host) {
            "manhwaus.net" -> scrapeManhwaUs(client, startUrl, userAgent, allowNsfw)
            "mangaread.org" -> scrapeMadara(client, startUrl, userAgent, allowNsfw)
            else -> {
                Logger.logError("Unsupported scrape source: $host. Only mangaread.org and manhwaus.net are supported for the --scrape command.")
                emptyList()
            }
        }

        Logger.logInfo("Total unique series found across all pages: ${results.size}")
        return results
    }
}
