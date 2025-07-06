package com.mangacombiner.service

import com.mangacombiner.util.logError
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO

@Service
class InfoPageGeneratorService {

    private companion object {
        const val IMG_WIDTH = 1200
        const val IMG_HEIGHT = 1920
        const val MARGIN = 50
        const val LINE_SPACING = 10
        const val TITLE_FONT_SIZE = 60
        const val DETAILS_FONT_SIZE = 36
        const val DETAILS_VALUE_X_OFFSET = 400
        const val TITLE_SPACING_MULTIPLIER = 4
    }

    data class InfoPageData(
        val title: String,
        val sourceUrl: String,
        val lastUpdated: String?,
        val chapterCount: Int,
        val pageCount: Int,
        val tempDir: File
    )

    /**
     * Creates an image file with metadata about the manga.
     * @return The generated image file, or null on failure.
     */
    fun create(data: InfoPageData): File? {
        val image = BufferedImage(IMG_WIDTH, IMG_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()

        return try {
            // Setup graphics
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.color = Color.WHITE
            g2d.fillRect(0, 0, IMG_WIDTH, IMG_HEIGHT)
            g2d.color = Color.BLACK

            var currentY = MARGIN

            // Title
            g2d.font = Font("Serif", Font.BOLD, TITLE_FONT_SIZE)
            currentY += g2d.fontMetrics.height
            g2d.drawString(data.title, MARGIN, currentY)
            currentY += LINE_SPACING * TITLE_SPACING_MULTIPLIER

            // Details
            g2d.font = Font("Serif", Font.PLAIN, DETAILS_FONT_SIZE)
            val detailsFontMetrics = g2d.fontMetrics

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
            val downloadDate = dateFormat.format(Date())

            val details = listOf(
                "Source" to data.sourceUrl,
                "Downloaded On" to downloadDate,
                "Last Site Update" to (data.lastUpdated ?: "N/A"),
                "Total Chapters" to data.chapterCount.toString(),
                "Total Pages" to data.pageCount.toString()
            )

            details.forEach { (key, value) ->
                currentY += detailsFontMetrics.height + LINE_SPACING
                g2d.drawString("$key:", MARGIN, currentY)
                g2d.drawString(value, MARGIN + DETAILS_VALUE_X_OFFSET, currentY)
            }

            // Create temp file
            val tempFile = Files.createTempFile(data.tempDir.toPath(), "infopage-", ".png").toFile()
            ImageIO.write(image, "png", tempFile)
            tempFile
        } catch (e: IOException) {
            logError("Failed to create info page image", e)
            null
        } finally {
            g2d.dispose()
        }
    }
}
