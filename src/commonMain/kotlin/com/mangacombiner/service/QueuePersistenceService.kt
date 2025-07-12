package com.mangacombiner.service

import com.mangacombiner.model.PersistedQueue
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.toSlug
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A service responsible for saving and loading the download queue to and from a JSON file.
 * This allows the queue to be restored after an application crash or restart.
 */
class QueuePersistenceService(private val platformProvider: PlatformProvider) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val queueFile: File
        get() = File(platformProvider.getTmpDir(), "queue_cache.json")

    private fun getMetadataFile(seriesPath: String): File {
        return File(seriesPath, "metadata.json")
    }

    /**
     * Saves the provided list of operations to the queue cache file.
     */
    fun saveQueue(operations: List<QueuedOperation>) {
        try {
            val persistedQueue = PersistedQueue(operations)
            val jsonString = json.encodeToString(persistedQueue)
            queueFile.writeText(jsonString)
            Logger.logDebug { "Successfully saved ${operations.size} item(s) to the queue cache." }
        } catch (e: Exception) {
            Logger.logError("Failed to save queue state.", e)
        }
    }

    /**
     * Loads the list of operations from the queue cache file.
     * @return A list of QueuedOperation objects, or null if the file doesn't exist or fails to parse.
     */
    fun loadQueue(): List<QueuedOperation>? {
        return if (queueFile.exists()) {
            try {
                val jsonString = queueFile.readText()
                if (jsonString.isBlank()) return null
                val persistedQueue = json.decodeFromString<PersistedQueue>(jsonString)
                Logger.logInfo("Loaded ${persistedQueue.operations.size} item(s) from queue cache.")
                persistedQueue.operations
            } catch (e: Exception) {
                Logger.logError("Failed to load queue state from cache.", e)
                null
            }
        } else {
            Logger.logDebug { "Queue cache file does not exist. Starting with an empty queue." }
            null
        }
    }

    /**
     * Deletes the queue cache file.
     */
    fun clearQueueCache() {
        try {
            if (queueFile.exists()) {
                if (queueFile.delete()) {
                    Logger.logInfo("Cleared queue cache file.")
                } else {
                    Logger.logError("Failed to delete queue cache file.")
                }
            }
        } catch (e: Exception) {
            Logger.logError("Failed to clear queue cache.", e)
        }
    }

    /**
     * Saves the metadata of a single download operation to its specific cache directory.
     */
    fun saveOperationMetadata(op: QueuedOperation) {
        val seriesSlug = op.seriesUrl.toSlug()
        val seriesDir = File(platformProvider.getTmpDir(), "manga-dl-$seriesSlug")
        if (!seriesDir.exists()) seriesDir.mkdirs()

        val metadataFile = getMetadataFile(seriesDir.absolutePath)
        try {
            val jsonString = json.encodeToString(op)
            metadataFile.writeText(jsonString)
            Logger.logDebug { "Saved metadata for ${op.customTitle}" }
        } catch (e: Exception) {
            Logger.logError("Failed to save operation metadata.", e)
        }
    }

    /**
     * Loads the metadata for a single download operation from its cache directory.
     */
    fun loadOperationMetadata(seriesPath: String): QueuedOperation? {
        val metadataFile = getMetadataFile(seriesPath)
        return if (metadataFile.exists()) {
            try {
                val jsonString = metadataFile.readText()
                json.decodeFromString<QueuedOperation>(jsonString)
            } catch (e: Exception) {
                Logger.logError("Failed to load metadata from $seriesPath", e)
                null
            }
        } else {
            null
        }
    }
}
