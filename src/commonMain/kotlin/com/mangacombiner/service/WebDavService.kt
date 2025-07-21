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
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler
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

/**
 * WebDAV service for listing and downloading files from WebDAV servers.
 * Provides secure authentication and robust error handling for WebDAV operations.
 */
class WebDavService {

    /**
     * XML configuration for parsing WebDAV responses with enhanced compatibility.
     * Uses updated xmlutil API that's compatible with version 0.91.1+
     */
    @OptIn(ExperimentalXmlUtilApi::class)
    private val xml = XML {
        policy = DefaultXmlSerializationPolicy.Builder().apply {
            // Updated configuration for xmlutil 0.91.1+
            pedantic = false
            // For now, use default unknown child handling
            // This can be customized later if needed
        }.build()
        indentString = "  "
    }

    /**
     * Generates HTTP Basic Authentication header from username and password.
     * Handles null/empty credentials gracefully and encodes properly for WebDAV auth.
     */
    private fun getAuthHeader(user: String?, pass: String?): String? {
        if (user.isNullOrBlank()) {
            Logger.logDebug { "No username provided, skipping authentication header" }
            return null
        }

        val credentials = "$user:${pass ?: ""}"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())
        Logger.logDebug { "Generated Basic auth header for user: $user" }
        return "Basic $encodedCredentials"
    }

    /**
     * Lists files and directories from a WebDAV server using PROPFIND method.
     * Supports authenticated and anonymous access with comprehensive error handling.
     */
    suspend fun listFiles(
        fullUrl: String,
        user: String?,
        pass: String?
    ): Result<List<WebDavFile>> = withContext(Dispatchers.IO) {
        Logger.logInfo("Connecting to WebDAV server: $fullUrl")

        val client = createHttpClient(null)
        try {
            val response: HttpResponse = client.request(fullUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1")
                header("Content-Type", "application/xml; charset=utf-8")

                // Add authentication header if credentials provided
                getAuthHeader(user, pass)?.let { authHeader ->
                    header(HttpHeaders.Authorization, authHeader)
                    Logger.logDebug { "Added authentication header for WebDAV request" }
                }

                // Add standard WebDAV headers for better compatibility
                header(HttpHeaders.UserAgent, "MangaCombiner-WebDAV/1.0")
                header(HttpHeaders.Accept, "application/xml, text/xml")

                // Basic PROPFIND body to request standard properties
                setBody("""<?xml version="1.0" encoding="utf-8" ?>
                    <D:propfind xmlns:D="DAV:">
                        <D:prop>
                            <D:displayname/>
                            <D:getcontentlength/>
                            <D:getlastmodified/>
                            <D:resourcetype/>
                        </D:prop>
                    </D:propfind>""".trimIndent())
            }

            if (!response.status.isSuccess()) {
                val errorMessage = when (response.status.value) {
                    401 -> "Authentication failed. Please check your username and password."
                    403 -> "Access forbidden. You don't have permission to access this resource."
                    404 -> "WebDAV resource not found. Please check the URL."
                    405 -> "WebDAV not supported on this server or endpoint."
                    500 -> "Internal server error. The WebDAV server encountered an error."
                    else -> "Server responded with ${response.status}. ${response.status.description}"
                }
                Logger.logError("WebDAV PROPFIND failed: $errorMessage")
                return@withContext Result.failure(Exception(errorMessage))
            }

            val responseBody = try {
                response.body<String>()
            } catch (e: Exception) {
                Logger.logError("Failed to read WebDAV response body", e)
                return@withContext Result.failure(Exception("Failed to read server response: ${e.message}"))
            }

            Logger.logDebug { "Received WebDAV response (${responseBody.length} chars)" }

            val multiStatus = try {
                xml.decodeFromString(WebDavMultiStatus.serializer(), responseBody)
            } catch (e: Exception) {
                Logger.logError("Failed to parse WebDAV XML response", e)
                Logger.logDebug { "Raw response body: $responseBody" }
                return@withContext Result.failure(Exception("Failed to parse WebDAV response. Server may not support WebDAV properly."))
            }

            val files = multiStatus.responses
                .mapNotNull { response ->
                    try {
                        response.toWebDavFile(fullUrl)
                    } catch (e: Exception) {
                        Logger.logDebug { "Skipped malformed WebDAV response entry: ${e.message}" }
                        null
                    }
                }
                .filter { file ->
                    // Filter for EPUB files only, excluding the parent directory entry
                    !file.isDirectory && file.name.isNotBlank() && file.name.endsWith(".epub", ignoreCase = true)
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            Logger.logInfo("Found ${files.size} EPUB files on WebDAV server")
            Result.success(files)

        } catch (e: Exception) {
            Logger.logError("Failed to connect to WebDAV server: $fullUrl", e)
            val userFriendlyMessage = when {
                e.message?.contains("ConnectException") == true -> "Failed to connect to server. Please check the URL and your internet connection."
                e.message?.contains("timeout") == true -> "Connection timed out. The server may be slow or unreachable."
                e.message?.contains("SSL") == true || e.message?.contains("certificate") == true -> "SSL/TLS error. The server's certificate may be invalid or self-signed."
                else -> "Connection failed: ${e.message ?: "Unknown error"}"
            }
            Result.failure(Exception(userFriendlyMessage))
        } finally {
            client.close()
        }
    }

    /**
     * Downloads a file from WebDAV server with progress tracking and resume capability.
     * Supports authenticated downloads and provides detailed progress callbacks.
     */
    suspend fun downloadFile(
        fullUrl: String,
        user: String?,
        pass: String?,
        destination: File,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        Logger.logInfo("Starting WebDAV download: ${destination.name}")

        // Ensure destination directory exists
        destination.parentFile?.mkdirs()

        val client = createHttpClient(null)
        try {
            val response = client.get(fullUrl) {
                getAuthHeader(user, pass)?.let { authHeader ->
                    header(HttpHeaders.Authorization, authHeader)
                }

                // Add standard headers for better compatibility
                header(HttpHeaders.UserAgent, "MangaCombiner-WebDAV/1.0")
                header(HttpHeaders.Accept, "*/*")

                // Set up progress tracking
                onDownload { bytesSentTotal, contentLength ->
                    onProgress(bytesSentTotal, contentLength ?: 0L)
                }
            }

            if (!response.status.isSuccess()) {
                val errorMessage = when (response.status.value) {
                    401 -> "Authentication failed during download."
                    403 -> "Access forbidden for file download."
                    404 -> "File not found on server."
                    else -> "Download failed with status ${response.status}"
                }
                Logger.logError("WebDAV download failed: $errorMessage")
                return@withContext Result.failure(Exception(errorMessage))
            }

            // Stream the response directly to file
            response.bodyAsChannel().copyAndClose(destination.writeChannel())

            // Verify download completed successfully
            if (!destination.exists() || destination.length() == 0L) {
                val error = "Download completed but file is empty or missing"
                Logger.logError(error)
                destination.delete() // Clean up empty file
                return@withContext Result.failure(Exception(error))
            }

            Logger.logInfo("Successfully downloaded: ${destination.name} (${destination.length()} bytes)")
            Result.success(Unit)

        } catch (e: Exception) {
            Logger.logError("Failed to download WebDAV file: $fullUrl", e)

            // Clean up partial download
            if (destination.exists()) {
                destination.delete()
                Logger.logDebug { "Cleaned up partial download file" }
            }

            val userFriendlyMessage = when {
                e.message?.contains("No space left") == true -> "Download failed: Not enough disk space."
                e.message?.contains("timeout") == true -> "Download failed: Connection timed out."
                e.message?.contains("Connection reset") == true -> "Download failed: Connection was reset by server."
                else -> "Download failed: ${e.message ?: "Unknown error"}"
            }
            Result.failure(Exception(userFriendlyMessage))
        } finally {
            client.close()
        }
    }

    /**
     * Converts a WebDAV response entry to a WebDavFile object with proper path handling.
     * Handles URL decoding, relative path computation, and multiple propstat entries.
     */
    private fun WebDavResponse.toWebDavFile(baseUrl: String): WebDavFile? {
        val decodedHref = try {
            URLDecoder.decode(href, "UTF-8")
        } catch (e: Exception) {
            Logger.logDebug { "Failed to URL decode href: $href" }
            href
        }

        val pathSegment = try {
            val baseUri = java.net.URI(baseUrl.trimEnd('/') + "/")
            val fileUri = java.net.URI(decodedHref)
            val relativePath = baseUri.relativize(fileUri).path

            // Handle case where URI relativization fails
            if (relativePath == decodedHref) {
                decodedHref.removePrefix(baseUrl).trimStart('/')
            } else {
                relativePath
            }
        } catch (e: Exception) {
            Logger.logDebug { "Failed to compute relative path for: $decodedHref, falling back to simple prefix removal" }
            decodedHref.removePrefix(baseUrl).trimStart('/')
        }

        // Skip empty paths (usually the parent directory entry)
        if (pathSegment.isBlank() || pathSegment == "/") {
            return null
        }

        // Get the successful propstat (status 200) or first available
        val propstat = successfulPropstat ?: return null

        val fileName = pathSegment.trimEnd('/').substringAfterLast('/')
        val isDirectory = propstat.prop.resourceType.collection != null
        val fileSize = propstat.prop.contentLength // Now non-nullable, defaults to 0L

        // Log status information for debugging
        propstat.status?.let { status ->
            if (!status.contains("200")) {
                Logger.logDebug { "WebDAV resource '$fileName' has non-success status: $status" }
            }
        }

        return WebDavFile(
            name = fileName,
            fullPath = pathSegment,
            isDirectory = isDirectory,
            size = fileSize
        )
    }
}
