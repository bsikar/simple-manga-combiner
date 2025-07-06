package com.mangacombiner.service

import java.io.File

/**
 * Represents the result of a file processing operation.
 *
 * @property success Indicates whether the operation was successful.
 * @property outputFile The file that was created, if any.
 * @property error An error message if the operation failed.
 */
data class ProcessResult(
    val success: Boolean,
    val outputFile: File? = null,
    val error: String? = null
)
