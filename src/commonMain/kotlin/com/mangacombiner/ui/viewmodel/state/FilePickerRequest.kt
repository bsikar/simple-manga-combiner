package com.mangacombiner.ui.viewmodel.state

sealed class FilePickerRequest {
    data class OpenFile(val purpose: FilePurpose) : FilePickerRequest()
    data class OpenFolder(val forPath: PathType) : FilePickerRequest()

    enum class PathType {
        DEFAULT_OUTPUT, CUSTOM_OUTPUT, JOB_OUTPUT, LIBRARY_SCAN_ADD
    }

    enum class FilePurpose {
        UPDATE_LOCAL, OPEN_DIRECTLY
    }
}
