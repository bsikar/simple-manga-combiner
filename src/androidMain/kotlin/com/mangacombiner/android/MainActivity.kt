package com.mangacombiner.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private val downloadService: com.mangacombiner.service.DownloadService by inject()
    private val processorService: com.mangacombiner.service.ProcessorService by inject()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen(
                onProcessClick = { state ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            // This is a simplified version of the processing logic for the GUI
                            // In a real app, you would create a shared ViewModel to handle this
                            if (state.source.startsWith("http", ignoreCase = true)) {
                                // Download logic here
                            } else {
                                // Local file processing logic here
                            }
                        } catch (e: Exception) {
                            Logger.logError("An error occurred: ${e.message}", e)
                        }
                    }
                }
            )
        }
    }
}
