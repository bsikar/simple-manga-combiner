package com.mangacombiner.ui.viewmodel.state

sealed class FilePickerRequest {
    data object OpenFile : FilePickerRequest()
    data class OpenFolder(val forPath: PathType) : FilePickerRequest()

    enum class PathType {
        DEFAULT_OUTPUT, CUSTOM_OUTPUT, JOB_OUTPUT
    }
}
