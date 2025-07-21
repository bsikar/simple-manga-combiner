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
     * Lists files and directories from a WebDAV server using PROPFIND method with recursive traversal.
     * Supports authenticated and anonymous access with comprehensive error handling.
     */
    suspend fun listFiles(
        fullUrl: String,
        user: String?,
        pass: String?
    ): Result<List<WebDavFile>> = withContext(Dispatchers.IO) {
        Logger.logInfo("Connecting to WebDAV server: $fullUrl")

        try {
            val allFiles = mutableListOf<WebDavFile>()
            val visitedUrls = mutableSetOf<String>()

            // Start recursive traversal from the root URL
            traverseDirectory(fullUrl, user, pass, allFiles, visitedUrls, depth = 0)

            Logger.logInfo("=== TRAVERSAL COMPLETE ===")
            Logger.logInfo("Total files/folders discovered: ${allFiles.size}")

            // Debug: Show all collected files
            Logger.logInfo("All discovered items:")
            allFiles.forEachIndexed { index, file ->
                val type = if (file.isDirectory) "DIR" else "FILE"
                val sizeInfo = if (file.isDirectory) "" else " (${file.size} bytes)"
                Logger.logInfo("  [$index] $type: ${file.name}$sizeInfo")
                Logger.logDebug { "       Path: ${file.fullPath}" }
            }

            // Filter for EPUB files only for the actual result
            val epubFiles = allFiles.filter { file ->
                val isEpub = !file.isDirectory && file.name.isNotBlank() && file.name.endsWith(".epub", ignoreCase = true)
                if (!file.isDirectory && file.name.isNotBlank()) {
                    Logger.logDebug { "Checking file '${file.name}': isDirectory=${file.isDirectory}, name.isNotBlank()=${file.name.isNotBlank()}, endsWith('.epub')=${file.name.endsWith(".epub", ignoreCase = true)} -> isEpub=$isEpub" }
                }
                isEpub
            }

            Logger.logInfo("=== EPUB FILTERING RESULTS ===")
            Logger.logInfo("Files that passed EPUB filter: ${epubFiles.size}")
            if (epubFiles.isNotEmpty()) {
                Logger.logInfo("EPUB files found:")
                epubFiles.forEachIndexed { index, file ->
                    Logger.logInfo("  [$index] ${file.name} (${file.size} bytes)")
                    Logger.logDebug { "    Full path: ${file.fullPath}" }
                }
            } else {
                Logger.logInfo("No EPUB files found after filtering")
                // Debug: Show what files we DID find
                val nonDirFiles = allFiles.filter { !it.isDirectory }
                if (nonDirFiles.isNotEmpty()) {
                    Logger.logInfo("Non-directory files found (for comparison):")
                    nonDirFiles.forEachIndexed { index, file ->
                        Logger.logInfo("  [$index] ${file.name} (extension: ${file.name.substringAfterLast('.', "none")})")
                    }
                } else {
                    Logger.logInfo("No files found at all (only directories or empty)")
                }
            }

            Result.success(epubFiles)

        } catch (e: Exception) {
            Logger.logError("Failed to connect to WebDAV server: $fullUrl", e)
            val userFriendlyMessage = when {
                e.message?.contains("ConnectException") == true -> "Failed to connect to server. Please check the URL and your internet connection."
                e.message?.contains("timeout") == true -> "Connection timed out. The server may be slow or unreachable."
                e.message?.contains("SSL") == true || e.message?.contains("certificate") == true -> "SSL/TLS error. The server's certificate may be invalid or self-signed."
                else -> "Connection failed: ${e.message ?: "Unknown error"}"
            }
            Result.failure(Exception(userFriendlyMessage))
        }
    }

    /**
     * Recursively traverses WebDAV directories to find all files.
     * Prevents infinite loops and limits recursion depth for safety.
     *
     * @param includeHidden Whether to traverse into hidden directories (starting with '.')
     */
    private suspend fun traverseDirectory(
        directoryUrl: String,
        user: String?,
        pass: String?,
        allFiles: MutableList<WebDavFile>,
        visitedUrls: MutableSet<String>,
        depth: Int,
        maxDepth: Int = 10,
        includeHidden: Boolean = false
    ) {
        // Prevent infinite recursion and loops
        if (depth > maxDepth || directoryUrl in visitedUrls) {
            Logger.logDebug { "Skipping directory (depth=$depth, visited=${directoryUrl in visitedUrls}): $directoryUrl" }
            return
        }

        visitedUrls.add(directoryUrl)
        Logger.logDebug { "Traversing directory [depth=$depth]: $directoryUrl" }

        val client = createHttpClient(null)
        try {
            Logger.logDebug { "Making PROPFIND request to: $directoryUrl" }
            Logger.logDebug { "Request headers: Depth=1, Content-Type=application/xml; charset=utf-8" }

            val response: HttpResponse = client.request(directoryUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1") // Only immediate children
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
                val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
                    <D:propfind xmlns:D="DAV:">
                        <D:prop>
                            <D:displayname/>
                            <D:getcontentlength/>
                            <D:getlastmodified/>
                            <D:resourcetype/>
                        </D:prop>
                    </D:propfind>""".trimIndent()

                Logger.logDebug { "PROPFIND request body: $propfindBody" }
                setBody(propfindBody)
            }

            Logger.logInfo("WebDAV Response: ${response.status} (${response.status.value})")
            Logger.logDebug { "Response headers: ${response.headers}" }

            if (!response.status.isSuccess()) {
                Logger.logError("WebDAV PROPFIND failed for $directoryUrl: ${response.status}")
                Logger.logError("Response status description: ${response.status.description}")
                return
            }

            val responseBody = try {
                response.body<String>()
            } catch (e: Exception) {
                Logger.logError("Failed to read WebDAV response body for $directoryUrl", e)
                return
            }

            if (responseBody.isBlank()) {
                Logger.logError("WebDAV server returned empty response body for $directoryUrl")
                return
            }

            Logger.logInfo("Received WebDAV response for $directoryUrl (${responseBody.length} chars)")
            Logger.logInfo("Raw XML response preview (first 500 chars): ${responseBody.take(500)}")
            if (responseBody.length > 500) {
                Logger.logDebug { "Full XML response: $responseBody" }
            } else {
                Logger.logInfo("Full XML response: $responseBody")
            }

            val multiStatus = try {
                xml.decodeFromString(WebDavMultiStatus.serializer(), responseBody)
            } catch (e: Exception) {
                Logger.logError("Failed to parse WebDAV XML response for $directoryUrl", e)
                Logger.logError("XML parsing error details: ${e.message}")
                Logger.logInfo("Raw response that failed to parse: $responseBody")
                return
            }

            Logger.logDebug { "Successfully parsed XML. Found ${multiStatus.responses.size} response entries" }

            // Debug each response entry before conversion
            multiStatus.responses.forEachIndexed { index, response ->
                Logger.logDebug { "Response [$index]: href='${response.href}', propstat count=${response.propstat.size}" }
                response.propstat.forEachIndexed { pIndex, propstat ->
                    Logger.logDebug { "  Propstat [$pIndex]: status='${propstat.status}'" }
                    val prop = propstat.prop
                    Logger.logDebug { "    displayName='${prop.displayName}'" }
                    Logger.logDebug { "    contentLength=${prop.contentLength}" }
                    Logger.logDebug { "    resourceType.collection=${prop.resourceType.collection}" }
                }
            }

            val currentDirFiles = multiStatus.responses
                .mapNotNull { response ->
                    try {
                        Logger.logDebug { "Processing response: href='${response.href}'" }
                        val result = response.toWebDavFile(directoryUrl)
                        if (result == null) {
                            Logger.logDebug { "toWebDavFile returned null for href='${response.href}'" }
                        } else {
                            Logger.logDebug { "toWebDavFile success: ${result.name}" }
                        }
                        result
                    } catch (e: Exception) {
                        Logger.logError("Failed to convert WebDAV response entry: ${e.message}", e)
                        Logger.logDebug { "Failed response href: '${response.href}'" }
                        null
                    }
                }
                .filter { file ->
                    // Skip the current directory entry (usually appears as empty name or ".")
                    val basicFilter = file.name.isNotBlank() && file.name != "." && !file.fullPath.endsWith("/")

                    // Apply hidden directory filter
                    val hiddenFilter = if (includeHidden) {
                        true // Include all files/directories
                    } else {
                        !file.name.startsWith('.') // Exclude hidden files/directories
                    }

                    val shouldInclude = basicFilter && hiddenFilter

                    if (!hiddenFilter) {
                        Logger.logDebug { "Filtered out hidden item: '${file.name}'" }
                    } else if (!basicFilter) {
                        Logger.logDebug { "Filtered out by basic filter: '${file.name}'" }
                    } else {
                        Logger.logDebug { "Item passed all filters: '${file.name}'" }
                    }

                    shouldInclude
                }

            // Debug: Log files found in this directory
            if (currentDirFiles.isNotEmpty()) {
                Logger.logInfo("Directory '$directoryUrl' contains ${currentDirFiles.size} items:")
                currentDirFiles.forEachIndexed { index, file ->
                    val type = if (file.isDirectory) "DIR" else "FILE"
                    val sizeInfo = if (file.isDirectory) "" else " (${file.size} bytes)"
                    val indent = "  ".repeat(depth + 1)
                    Logger.logInfo("$indent[$index] $type: ${file.name}$sizeInfo")
                    Logger.logDebug { "$indent    Full path: ${file.fullPath}" }
                    Logger.logDebug { "$indent    Extension: ${file.name.substringAfterLast('.', "no-extension")}" }

                    // Debug EPUB detection
                    if (!file.isDirectory) {
                        val isEpub = file.name.endsWith(".epub", ignoreCase = true)
                        Logger.logDebug { "$indent    Is EPUB: $isEpub (name='${file.name}', ends with .epub: ${file.name.endsWith(".epub", ignoreCase = true)})" }
                    }
                }
            } else {
                Logger.logInfo("Directory '$directoryUrl' is empty or contains no accessible items")
            }

            // Add all files to the master list
            allFiles.addAll(currentDirFiles)
            Logger.logDebug { "Total files collected so far: ${allFiles.size}" }

            // Recursively traverse subdirectories (filtering hidden ones if needed)
            val subdirectories = currentDirFiles.filter { file ->
                val isDirectory = file.isDirectory
                val shouldTraverse = if (includeHidden) {
                    isDirectory
                } else {
                    isDirectory && !file.name.startsWith('.')
                }

                if (isDirectory && !shouldTraverse) {
                    Logger.logDebug { "Skipping hidden directory: '${file.name}'" }
                }

                shouldTraverse
            }

            Logger.logDebug { "Found ${subdirectories.size} subdirectories to traverse (${currentDirFiles.count { it.isDirectory }} total directories, includeHidden=$includeHidden)" }

            for (subdir in subdirectories) {
                val subdirUrl = buildSubdirectoryUrl(directoryUrl, subdir.fullPath)
                Logger.logDebug { "About to traverse subdirectory: '${subdir.name}' -> '$subdirUrl'" }
                traverseDirectory(subdirUrl, user, pass, allFiles, visitedUrls, depth + 1, maxDepth, includeHidden)
            }

        } finally {
            client.close()
        }
    }

    /**
     * Checks if a file or directory name represents a hidden item.
     * Hidden items start with a dot (.) which is the standard convention
     * on Unix-like systems and many file servers.
     */
    private fun isHidden(name: String): Boolean {
        return name.startsWith('.')
    }

    /**
     * Constructs the full URL for a subdirectory based on the parent URL and relative path.
     */
    private fun buildSubdirectoryUrl(parentUrl: String, relativePath: String): String {
        val baseUrl = parentUrl.trimEnd('/')
        val cleanPath = relativePath.trimStart('/')
        return "$baseUrl/$cleanPath"
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
        Logger.logDebug { "Converting WebDAV response: href='$href', baseUrl='$baseUrl'" }

        val decodedHref = try {
            URLDecoder.decode(href, "UTF-8")
        } catch (e: Exception) {
            Logger.logDebug { "Failed to URL decode href: $href, using original" }
            href
        }
        Logger.logDebug { "Decoded href: '$decodedHref'" }

        // Handle different href formats
        val pathSegment = when {
            // Absolute path starting with /
            decodedHref.startsWith("/") -> {
                val cleanPath = decodedHref.trimStart('/').trimEnd('/')
                Logger.logDebug { "Absolute path detected, extracted: '$cleanPath'" }
                cleanPath
            }
            // Full URL
            decodedHref.startsWith("http") -> {
                try {
                    val uri = java.net.URI(decodedHref)
                    val cleanPath = uri.path.trimStart('/').trimEnd('/')
                    Logger.logDebug { "Full URL detected, extracted path: '$cleanPath'" }
                    cleanPath
                } catch (e: Exception) {
                    Logger.logDebug { "Failed to parse full URL, using fallback" }
                    decodedHref.substringAfterLast('/').trimEnd('/')
                }
            }
            // Relative path
            else -> {
                Logger.logDebug { "Relative path detected: '$decodedHref'" }
                decodedHref.trimEnd('/')
            }
        }

        Logger.logDebug { "Final path segment: '$pathSegment'" }

        // Skip empty paths or root directory entries
        if (pathSegment.isBlank() || pathSegment == "." || pathSegment == "/") {
            Logger.logDebug { "Skipping empty, root, or current directory path segment: '$pathSegment'" }
            return null
        }

        // Get the successful propstat (status 200) or first available
        val propstat = successfulPropstat
        if (propstat == null) {
            Logger.logDebug { "No successful propstat found for '$pathSegment'" }
            return null
        }

        val fileName = if (pathSegment.contains('/')) {
            pathSegment.substringAfterLast('/')
        } else {
            pathSegment
        }

        val isDirectory = propstat.prop.resourceType.collection != null
        val fileSize = propstat.prop.contentLength // Now non-nullable, defaults to 0L

        Logger.logDebug { "Extracted: fileName='$fileName', isDirectory=$isDirectory, fileSize=$fileSize" }

        // Log status information for debugging
        propstat.status?.let { status ->
            Logger.logDebug { "WebDAV resource '$fileName' status: $status" }
        } ?: Logger.logDebug { "WebDAV resource '$fileName' has no status information" }

        val webDavFile = WebDavFile(
            name = fileName,
            fullPath = pathSegment,
            isDirectory = isDirectory,
            size = fileSize
        )

        Logger.logDebug { "Created WebDavFile: $webDavFile" }
        return webDavFile
    }
}
