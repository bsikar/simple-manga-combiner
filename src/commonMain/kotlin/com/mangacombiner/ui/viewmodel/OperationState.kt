package com.mangacombiner.ui.viewmodel

/**
 * Represents the status of a long-running operation like a download.
 */
enum class OperationState {
    IDLE,
    RUNNING,
    PAUSED,
    CANCELLING
}
