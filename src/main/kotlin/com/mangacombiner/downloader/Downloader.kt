package com.mangacombiner.downloader

import com.mangacombiner.util.logDebug
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.File
import java.net.URL

object Downloader {
    private val client = HttpClient(CIO) {
        engine {
            // Set a finite timeout to prevent the application from hanging indefinitely.
            requestTimeout = 30_000 // 30 seconds
        }
        defaultRequest {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36")
        }
    }

    suspend fun findChapterUrls(seriesUrl: String): List<String> {
        return try {
            val response: HttpResponse = client.get(seriesUrl)
            val html = response.bodyAsText()
            val doc = Jsoup.parse(html, seriesUrl)
            val chapterElements = doc.select("li.wp-manga-chapter a")
            if (chapterElements.isEmpty()) {
                println("Warning: Could not find any chapter links using selector 'li.wp-manga-chapter a'. The site may not be supported or its layout has changed.")
                return emptyList()
            }
            chapterElements.map { it.absUrl("href") }.reversed()
        } catch (e: Exception) {
            println("Error: Failed to fetch or parse the series page. Check the URL and your connection.")
            logDebug { "Exception in findChapterUrls: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun findImageUrls(chapterUrl: String): List<String> {
        val response: HttpResponse = client.get(chapterUrl)
        val html = response.bodyAsText()
        val doc = Jsoup.parse(html, chapterUrl)
        // This selector is specific to the WP Manga reader theme.
        return doc.select("img.wp-manga-chapter-img").mapNotNull { it.absUrl("src").trim() }
    }

    suspend fun downloadChapter(chapterUrl: String, baseOutputDir: File): File? {
        return try {
            val slug = URL(chapterUrl).path.trim('/').substringAfterLast('/')
            val chapterDir = File(baseOutputDir, slug)
            chapterDir.mkdirs()

            val imageUrls = findImageUrls(chapterUrl)
            if (imageUrls.isEmpty()) {
                println("  Warning: No images found for chapter '$slug'. The page might be empty or the site layout is not supported.")
                return null
            }
            logDebug { "Found ${imageUrls.size} images for chapter $slug." }

            withContext(Dispatchers.IO) {
                imageUrls.mapIndexed { index, imageUrl ->
                    async {
                        val extension = imageUrl.substringAfterLast('.', "jpg")
                        val imageFile = File(chapterDir, "page_%03d.%s".format(index + 1, extension))
                        if (!imageFile.exists()) {
                            try {
                                val response: HttpResponse = client.get(imageUrl)
                                val byteChannel: ByteReadChannel = response.bodyAsChannel()
                                byteChannel.copyAndClose(imageFile.writeChannel())
                            } catch (e: Exception) {
                                println("  Error downloading image: $imageUrl")
                            }
                        }
                    }
                }.awaitAll()
            }
            chapterDir
        } catch (e: Exception) {
            println("Error: Failed to process chapter $chapterUrl. It may be unavailable.")
            logDebug { "Exception in downloadChapter: ${e.message}" }
            null
        }
    }
}
