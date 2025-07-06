package com.mangacombiner.ui

import androidx.compose.ui.window.application
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ProcessorService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("gui")
class GuiRunner(
    private val context: ConfigurableApplicationContext,
    private val downloadService: DownloadService,
    private val processorService: ProcessorService
) : ApplicationListener<ApplicationReadyEvent> {

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        application {
            MangaCombinerWindow(
                downloadService = downloadService,
                processorService = processorService,
                onClose = {
                    exitApplication()
                    context.close()
                }
            )
        }
    }
}
