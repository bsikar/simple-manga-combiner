package com.mangacombiner.service

import com.mangacombiner.model.WebDavMultiStatus
import com.mangacombiner.model.WebDavResponse
import com.mangacombiner.util.Logger
import com.mangacombiner.util.createHttpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.DefaultXmlSerializationPolicy
import nl.adaptivity.xmlutil.serialization.XML
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

data class WebDavFile(
    val href: String,
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val size: Long,
)

class WebDavService {

    @OptIn(ExperimentalXmlUtilApi::class)
    private val xml = XML {
        policy = DefaultXmlSerializationPolicy.Builder().apply { pedantic = false }.build()
        indentString = "  "
    }

    private fun getAuthHeader(user: String?, pass: String?): String? {
        if (user.isNullOrBlank()) return null
        val credentials = "$user:${pass ?: ""}"
        return "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
    }

    suspend fun listFiles(
        fullUrl: String,
        user: String?,
        pass: String?,
        includeHidden: Boolean
    ): Result<List<WebDavFile>> = withContext(Dispatchers.IO) {
        Logger.logInfo("Listing files from WebDAV URL: $fullUrl")
        val client = createHttpClient(null)
        try {
            val response: HttpResponse = client.request(fullUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1")
                getAuthHeader(user, pass)?.let { header(HttpHeaders.Authorization, it) }
                header(HttpHeaders.UserAgent, "MangaCombiner-WebDAV/1.0")
                setBody("""<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:getcontentlength/><D:resourcetype/></D:prop></D:propfind>""")
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Server responded with status ${response.status}"))
            }

            val responseBody = response.body<String>()
            val multiStatus = xml.decodeFromString(WebDavMultiStatus.serializer(), responseBody)

            val allItems = multiStatus.responses.mapNotNull { it.toWebDavFile(fullUrl) }
            val visibleItems = allItems.filter { includeHidden || !it.name.startsWith('.') }

            val sortedItems = visibleItems.sortedWith(
                compareBy<WebDavFile> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )

            Result.success(sortedItems)
        } catch (e: Exception) {
            Logger.logError("Failed to list files from WebDAV: $fullUrl", e)
            Result.failure(Exception("Failed to list files: ${e.message ?: "Unknown error"}"))
        } finally {
            client.close()
        }
    }

    suspend fun scanDirectoryRecursively(
        fullUrl: String,
        user: String?,
        pass: String?,
        includeHidden: Boolean
    ): Result<List<WebDavFile>> = withContext(Dispatchers.IO) {
        try {
            val allFiles = mutableListOf<WebDavFile>()
            traverseAndCollectFiles(fullUrl, user, pass, allFiles, mutableSetOf(), 0, includeHidden)
            Result.success(allFiles.filter { !it.isDirectory })
        } catch (e: Exception) {
            Logger.logError("Failed to recursively scan directory: $fullUrl", e)
            Result.failure(e)
        }
    }

    private suspend fun traverseAndCollectFiles(
        directoryUrl: String,
        user: String?,
        pass: String?,
        allFiles: MutableList<WebDavFile>,
        visitedUrls: MutableSet<String>,
        depth: Int,
        includeHidden: Boolean,
        maxDepth: Int = 20
    ) {
        if (depth > maxDepth || !visitedUrls.add(directoryUrl)) return

        val client = createHttpClient(null)
        try {
            val response = client.request(directoryUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1")
                getAuthHeader(user, pass)?.let { header(HttpHeaders.Authorization, it) }
                header(HttpHeaders.UserAgent, "MangaCombiner-WebDAV/1.0")
                setBody("""<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:getcontentlength/><D:resourcetype/></D:prop></D:propfind>""")
            }
            if (!response.status.isSuccess()) return

            val multiStatus = xml.decodeFromString(WebDavMultiStatus.serializer(), response.body<String>())
            val serverUri = URI(directoryUrl)
            val serverRoot = "${serverUri.scheme}://${serverUri.authority}"

            val itemsInDir = multiStatus.responses
                .mapNotNull { it.toWebDavFile(directoryUrl) }
                .filter { includeHidden || !it.name.startsWith('.') }

            allFiles.addAll(itemsInDir)

            val subdirectories = itemsInDir.filter { it.isDirectory }
            for (subdir in subdirectories) {
                val subdirUrl = serverRoot + subdir.href
                traverseAndCollectFiles(subdirUrl, user, pass, allFiles, visitedUrls, depth + 1, includeHidden, maxDepth)
            }
        } finally {
            client.close()
        }
    }


    suspend fun downloadFile(
        fullUrl: String,
        user: String?,
        pass: String?,
        destination: File,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val client = createHttpClient(null)
        try {
            val response = client.get(fullUrl) {
                getAuthHeader(user, pass)?.let { header(HttpHeaders.Authorization, it) }
                header(HttpHeaders.UserAgent, "MangaCombiner-WebDAV/1.0")
                onDownload { bytesSentTotal, contentLength -> onProgress(bytesSentTotal, contentLength ?: 0L) }
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Download failed with status ${response.status}"))
            }

            response.bodyAsChannel().copyAndClose(destination.writeChannel())
            if (!destination.exists() || destination.length() == 0L) {
                destination.delete()
                return@withContext Result.failure(Exception("Download completed but file is empty or missing"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            destination.delete()
            Result.failure(Exception("Download failed: ${e.message ?: "Unknown error"}"))
        } finally {
            client.close()
        }
    }

    private fun WebDavResponse.toWebDavFile(baseUrl: String): WebDavFile? {
        val decodedHref = try { URLDecoder.decode(this.href, "UTF-8") } catch (e: Exception) { this.href }
        val fullPath = decodedHref.trim('/')
        val baseUrlPath = try { URI(baseUrl).path?.trim('/') ?: "" } catch (e: Exception) { "" }

        if (fullPath == baseUrlPath) return null

        val name = fullPath.substringAfterLast('/')
        if (name.isBlank()) return null

        val propstat = successfulPropstat ?: return null
        return WebDavFile(
            href = this.href,
            name = name,
            fullPath = fullPath,
            isDirectory = propstat.prop.resourceType.collection != null,
            size = propstat.prop.contentLength
        )
    }
}
