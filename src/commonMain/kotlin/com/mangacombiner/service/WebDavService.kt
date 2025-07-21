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
import java.net.URLDecoder
import java.util.Base64

data class WebDavFile(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val size: Long,
)

class WebDavService {
    @OptIn(ExperimentalXmlUtilApi::class)
    private val xml = XML {
        policy = DefaultXmlSerializationPolicy.Builder().apply {
            pedantic = false
            ignoreUnknownNames = true
        }.build()
        indentString = "  "
    }

    private fun getAuthHeader(user: String?, pass: String?): String? {
        if (user.isNullOrBlank()) return null
        val credentials = "$user:${pass ?: ""}"
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
    }

    suspend fun listFiles(
        fullUrl: String,
        user: String?,
        pass: String?
    ): Result<List<WebDavFile>> = withContext(Dispatchers.IO) {
        val client = createHttpClient(null)
        try {
            val response: HttpResponse = client.request(fullUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1")
                getAuthHeader(user, pass)?.let {
                    header(HttpHeaders.Authorization, it)
                }
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Server responded with ${response.status}"))
            }

            val body = response.body<String>()
            val multiStatus = xml.decodeFromString(WebDavMultiStatus.serializer(), body)

            val files = multiStatus.responses
                .mapNotNull { it.toWebDavFile(fullUrl) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

            Result.success(files)
        } catch (e: Exception) {
            Logger.logError("Failed to list WebDAV files", e)
            Result.failure(e)
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
        val client = createHttpClient(null)
        try {
            client.get(fullUrl) {
                getAuthHeader(user, pass)?.let {
                    header(HttpHeaders.Authorization, it)
                }
                onDownload { bytesSentTotal, contentLength ->
                    onProgress(bytesSentTotal, contentLength ?: 0L)
                }
            }.bodyAsChannel().copyAndClose(destination.writeChannel())
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.logError("Failed to download WebDAV file: $fullUrl", e)
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    private fun WebDavResponse.toWebDavFile(baseUrl: String): WebDavFile? {
        val decodedHref = URLDecoder.decode(href, "UTF-8")

        val pathSegment = try {
            val baseUri = java.net.URI(baseUrl.trimEnd('/') + "/")
            val fileUri = java.net.URI(decodedHref)
            baseUri.relativize(fileUri).path
        } catch (e: Exception) {
            decodedHref.removePrefix(baseUrl).trimStart('/')
        }

        if (pathSegment.isBlank()) return null

        return WebDavFile(
            name = pathSegment.trimEnd('/').substringAfterLast('/'),
            fullPath = pathSegment,
            isDirectory = propstat.prop.resourceType.collection != null,
            size = propstat.prop.contentLength ?: 0L
        )
    }
}
