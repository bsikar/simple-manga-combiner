package com.mangacombiner.android

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.Logger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModel<MainViewModel>()

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handleFileUri(it) }
        }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                val path = it.toString()
                viewModel.onFolderSelected(path, FilePickerRequest.PathType.DEFAULT_OUTPUT)
                Logger.logInfo("Note: On Android, output is handled via the selected folder's URI.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            viewModel.filePickerRequest.collectLatest { request ->
                when (request) {
                    is FilePickerRequest.OpenFile -> filePickerLauncher.launch(
                        arrayOf("application/epub+zip", "application/vnd.comicbook+zip", "application/x-cbz")
                    )
                    is FilePickerRequest.OpenFolder -> folderPickerLauncher.launch(null)
                }
            }
        }

        setContent {
            val state by viewModel.state.collectAsState()
            val density = LocalDensity.current
            val fontMultiplier = when (state.fontSizePreset) {
                "X-Small" -> 0.75f
                "Small" -> 0.90f
                "Large" -> 1.15f
                "X-Large" -> 1.30f
                else -> 1.0f // Medium
            }
            val newDensity = Density(
                density.density, // On Android, we respect the system's density
                density.fontScale * fontMultiplier
            )

            CompositionLocalProvider(LocalDensity provides newDensity) {
                AppTheme(
                    settingsTheme = state.theme,
                    systemLightTheme = state.systemLightTheme,
                    systemDarkTheme = state.systemDarkTheme
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }

    private fun handleFileUri(uri: Uri) {
        try {
            // Copy the selected file to the app's private cache so we can get a real file path
            val fileName = getFileName(uri) ?: "temp_manga_file"
            val tempFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            // Now pass the real, accessible file path to the ViewModel
            viewModel.onFileSelected(tempFile.absolutePath)
        } catch (e: Exception) {
            Logger.logError("Failed to process selected file.", e)
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor.use {
                if (it != null && it.moveToFirst()) {
                    val colIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (colIndex >= 0) {
                        result = it.getString(colIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result
    }
}
