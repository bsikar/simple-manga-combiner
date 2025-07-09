package com.mangacombiner.util

import java.io.File

/**
 * An abstraction for moving a completed file from a temporary location
 * to a final, user-specified destination, handling platform differences
 * like Android's Storage Access Framework.
 */
expect class FileMover {
    /**
     * Moves a file from a temporary location to a user-specified final destination.
     *
     * @param sourceFile The file to move (located in a standard cache/temp directory).
     * @param destinationIdentifier The path (desktop) or URI string (Android) for the destination.
     * @param finalFileName The desired name for the file at the destination.
     * @return A string indicating the final path or a confirmation message on success, or an empty string on failure.
     */
    fun moveToFinalDestination(sourceFile: File, destinationIdentifier: String, finalFileName: String): String
}
