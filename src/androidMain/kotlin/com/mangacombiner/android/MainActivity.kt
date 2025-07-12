package com.mangacombiner.android

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.util.Logger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModel<MainViewModel>()

    private var currentFolderPickerRequestType: FilePickerRequest.PathType? = null

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handleFileUri(it) }
        }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                currentFolderPickerRequestType?.let { type ->
                    val path = it.toString()
                    viewModel.onFolderSelected(path, type)
                    Logger.logInfo("Note: On Android, output is handled via the selected folder's URI.")
                }
                currentFolderPickerRequestType = null
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
                    is FilePickerRequest.OpenFolder -> {
                        currentFolderPickerRequestType = request.forPath
                        folderPickerLauncher.launch(null)
                    }
                }
            }
        }

        setContent {
            val state by viewModel.state.collectAsState()
            val density = LocalDensity.current
            val fontMultiplier = when (state.fontSizePreset) {
                "XX-Small" -> 0.50f
                "X-Small" -> 0.75f
                "Small" -> 0.90f
                "Large" -> 1.15f
                "X-Large" -> 1.30f
                "XX-Large" -> 1.45f
                else -> 1.0f // Medium
            }
            val newDensity = Density(
                density.density, // On Android, we respect the system's density
                density.fontScale * fontMultiplier
            )

            CompositionLocalProvider(LocalDensity provides newDensity) {
                AppTheme(settingsTheme = state.theme) {
                    // Read the theme colors here, in the @Composable scope
                    val surfaceColor = MaterialTheme.colors.surface
                    val isLightTheme = MaterialTheme.colors.isLight

                    SideEffect {
                        val window = this@MainActivity.window
                        // Use the values read from the theme
                        window.statusBarColor = surfaceColor.toArgb()
                        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isLightTheme
                    }

                    MainScreen(viewModel)
                }
            }
        }
    }

    private fun handleFileUri(uri: Uri) {
        try {
            val fileName = getFileName(uri) ?: "temp_manga_file"
            val tempFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
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
