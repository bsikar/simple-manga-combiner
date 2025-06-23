package com.mangacombiner.downloader

import com.mangacombiner.util.logDebug
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * Handles downloading manga chapters from supported websites.
 * Currently supports sites using the WP Manga Reader theme.
 */
object Downloader {
    // Configuration constants
    private const val REQUEST_TIMEOUT_MS = 30_000L // 30 seconds
    private const val CONNECT_TIMEOUT_MS = 10_000L // 10 seconds
    private const val SOCKET_TIMEOUT_MS = 30_000L // 30 seconds
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // CSS selectors for WP Manga Reader theme
    private const val CHAPTER_LIST_SELECTOR = "li.wp-manga-chapter a"
    private const val IMAGE_SELECTOR = "img.wp-manga-chapter-img"

    // File naming patterns
    private const val IMAGE_FILE_PATTERN = "page_%03d.%s"

    // Retry configuration
    private const val MAX_DOWNLOAD_RETRIES = 3
    private const val RETRY_DELAY_MS = 1_000L

    /**
     * HTTP client configured for manga downloading.
     */
    private val client = HttpClient(CIO) {
        engine {
            // Global request timeout
            requestTimeout = REQUEST_TIMEOUT_MS

            endpoint {
                connectTimeout = CONNECT_TIMEOUT_MS
                socketTimeout = SOCKET_TIMEOUT_MS
                maxConnectionsPerRoute = 20
                pipelineMaxSize = 20
                keepAliveTime = 5_000
            }
        }

        defaultRequest {
            header(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            header(HttpHeaders.AcceptEncoding, "gzip, deflate")
            header(HttpHeaders.CacheControl, "no-cache")
            header(HttpHeaders.Pragma, "no-cache")
        }

        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }

        followRedirects = true
    }

    /**
     * Exception thrown when chapter URLs cannot be found.
     */
    class ChapterListException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Exception thrown when images cannot be found in a chapter.
     */
    class ImageListException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Exception thrown when download fails.
     */
    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Validates if a URL is properly formatted.
     */
    private fun validateUrl(url: String): Boolean =
        try {
            URL(url).protocol in listOf("http", "https")
        } catch (_: Exception) {
            false
        }

    /**
     * Fetches and parses an HTML page.
     */
    private suspend fun fetchHtmlDocument(url: String): Document {
        require(validateUrl(url)) { "Invalid URL: $url" }
        logDebug { "Fetching HTML: $url" }

        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw DownloadException("HTTP ${response.status.value}: ${response.status.description}")
        }

        return Jsoup.parse(response.bodyAsText(), url)
    }

    /**
     * Finds all chapter URLs for a manga series.
     */
    suspend fun findChapterUrls(seriesUrl: String): List<String> =
        try {
            val doc = fetchHtmlDocument(seriesUrl)
            val elements = doc.select(CHAPTER_LIST_SELECTOR)
            if (elements.isEmpty()) throw ChapterListException("No chapters found at $seriesUrl")

            elements.mapNotNull { it.absUrl("href").takeIf(::validateUrl) }
                .reversed()
                .also { if (it.isEmpty()) throw ChapterListException("No valid chapter URLs found at $seriesUrl") }
        } catch (e: DownloadException) {
            throw ChapterListException("Failed to fetch series page: ${e.message}", e)
        }

    /**
     * Finds all image URLs in a chapter page.
     */
    private suspend fun findImageUrls(chapterUrl: String): List<String> {
        try {
            val doc = fetchHtmlDocument(chapterUrl)
            val urls = doc.select(IMAGE_SELECTOR)
                .mapNotNull { it.absUrl("src").takeIf(::validateUrl) }

            if (urls.isNotEmpty()) {
                return urls
            }
        } catch (e: DownloadException) {
            throw ImageListException("Failed to fetch chapter page: ${e.message}", e)
        }
        throw ImageListException("No images found in chapter: $chapterUrl")
    }

    /**
     * Downloads a single image with retry logic.
     */
    private suspend fun downloadImage(imageUrl: String, outputFile: File) {
        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_RETRIES) { attempt ->
            try {
                val response = client.get(imageUrl) {
                    header(HttpHeaders.Referrer, imageUrl.substringBeforeLast('/'))
                }
                if (!response.status.isSuccess()) {
                    throw DownloadException("HTTP ${response.status.value}: ${response.status.description}")
                }

                val temp = File(outputFile.parentFile, "${outputFile.name}.tmp")
                try {
                    response.bodyAsChannel().toInputStream().use { input ->
                        temp.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                    if (!temp.exists() || temp.length() == 0L) {
                        throw DownloadException("Downloaded file is empty")
                    }
                    if (outputFile.exists()) outputFile.delete()
                    temp.renameTo(outputFile)
                    return
                } finally {
                    if (temp.exists()) temp.delete()
                }
            } catch (t: Throwable) {
                lastError = t
                logDebug { "Download failed (attempt ${attempt + 1}): ${t.message}" }
                if (attempt < MAX_DOWNLOAD_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw DownloadException("Failed to download after $MAX_DOWNLOAD_RETRIES retries: ${lastError?.message}", lastError)
    }

    /**
     * Downloads all images from a chapter.
     */
    suspend fun downloadChapter(chapterUrl: String, baseDir: File): File? {
        val slug = URL(chapterUrl).path.trim('/').substringAfterLast('/')
            .takeIf { it.isNotBlank() } ?: return null

        val chapterDir = File(baseDir, slug)
        if (!chapterDir.exists() && !chapterDir.mkdirs()) {
            throw IOException("Failed to create directory: ${chapterDir.absolutePath}")
        }

        val imageUrls = try {
            findImageUrls(chapterUrl)
        } catch (e: ImageListException) {
            logDebug { "No images for chapter $slug: ${e.message}" }
            return null
        }

        coroutineScope {
            val sem = Semaphore(10)
            imageUrls.mapIndexed { idx, url ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val ext = url.substringAfterLast('.', "jpg").substringBefore('?')
                        val file = File(chapterDir, IMAGE_FILE_PATTERN.format(idx + 1, ext))
                        if (!file.exists()) {
                            downloadImage(url, file)
                        } else {
                            logDebug { "Skipping existing file: ${file.name}" }
                        }
                    }
                }
            }.awaitAll()
        }

        return chapterDir.takeIf { it.listFiles()?.any { f -> f.length() > 0 } == true }
    }

    /**
     * Closes the HTTP client and releases resources.
     */
    fun close() {
        client.close()
    }
}
