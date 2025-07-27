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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/**
 * Main activity for the Android application providing file picker integration,
 * dynamic theming support, and proper status bar handling across Android versions.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModel<MainViewModel>()

    private var currentFolderPickerRequestType: FilePickerRequest.PathType? = null

    /**
     * Launcher for picking individual files with support for EPUB formats.
     * Handles the file selection result and processes the selected file URI.
     */
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handleFileUri(it) }
        }

    /**
     * Launcher for picking folders/directories for output path selection.
     * Stores the folder URI for later use in file operations.
     */
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

        // Disable edge-to-edge to give the status bar a solid background
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Set up file picker request handling
        lifecycleScope.launch {
            viewModel.filePickerRequest.collectLatest { request ->
                when (request) {
                    is FilePickerRequest.OpenFile -> filePickerLauncher.launch(
                        arrayOf("application/epub+zip")
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

            // Calculate font scaling based on user preference
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
                density.density, // Respect system's density
                density.fontScale * fontMultiplier
            )

            CompositionLocalProvider(LocalDensity provides newDensity) {
                AppTheme(settingsTheme = state.theme) {
                    // Read theme colors within the Composable scope
                    val surfaceColor = MaterialTheme.colors.surface
                    val isLightTheme = MaterialTheme.colors.isLight

                    SideEffect {
                        configureStatusBar(surfaceColor, isLightTheme)
                    }

                    MainScreen(viewModel)
                }
            }
        }
    }

    /**
     * Configures the status bar appearance using the modern approach that avoids all deprecated APIs.
     * Uses WindowInsetsController for consistent behavior across Android versions.
     */
    private fun configureStatusBar(surfaceColor: Color, isLightTheme: Boolean) {
        val window = this.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        // Set the status bar color to match the app's surface color
        window.statusBarColor = surfaceColor.toArgb()

        // Configure status bar icons to be dark on light themes
        insetsController.isAppearanceLightStatusBars = isLightTheme

        // Configure system bars behavior for better UX
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT

        Logger.logDebug { "Configured status bar for ${if (isLightTheme) "light" else "dark"} theme" }
    }

    /**
     * Processes the selected file URI by copying it to a temporary cache location
     * and notifying the ViewModel with the local file path.
     */
    private fun handleFileUri(uri: Uri) {
        try {
            val fileName = getFileName(uri) ?: "temp_manga_file_${System.currentTimeMillis()}"
            val tempFile = File(cacheDir, fileName)

            // Ensure cache directory exists
            cacheDir.mkdirs()

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val bytesTransferred = inputStream.copyTo(outputStream)
                    Logger.logDebug { "Successfully copied file: $fileName ($bytesTransferred bytes)" }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                viewModel.onFileSelected(tempFile.absolutePath)
                Logger.logInfo("File processed successfully: ${tempFile.name}")
            } else {
                throw Exception("File copy resulted in empty or missing file")
            }
        } catch (e: Exception) {
            Logger.logError("Failed to process selected file from URI: $uri", e)
            // Could show a user-friendly error dialog here if needed
        }
    }

    /**
     * Extracts the display name from a content URI using the content resolver.
     * Falls back to parsing the URI path if the content provider doesn't provide a display name.
     */
    private fun getFileName(uri: Uri): String? {
        var result: String? = null

        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor.use { c ->
                if (c != null && c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = c.getString(nameIndex)
                    }
                }
            }
        }

        // Fallback to extracting filename from URI path
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                result = result.substring(cut + 1)
            }
        }

        return result
    }
}
