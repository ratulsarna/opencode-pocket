package com.ratulsarna.ocmobile.platform

import com.ratulsarna.ocmobile.domain.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * JVM/Desktop implementation for ClipboardImageReader using AWT clipboard.
 */
actual open class ClipboardImageReader actual constructor() {

    actual open suspend fun hasImage(): Boolean = withContext(Dispatchers.IO) {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
        } catch (e: Exception) {
            false
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    actual open suspend fun readImage(): Attachment? = withContext(Dispatchers.IO) {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                return@withContext null
            }

            val image = clipboard.getData(DataFlavor.imageFlavor) as? Image
                ?: return@withContext null

            // Convert to BufferedImage
            val bufferedImage = if (image is BufferedImage) {
                image
            } else {
                val width = image.getWidth(null)
                val height = image.getHeight(null)
                if (width <= 0 || height <= 0) return@withContext null

                BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
                    createGraphics().apply {
                        drawImage(image, 0, 0, null)
                        dispose()
                    }
                }
            }

            // Encode to PNG bytes
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(bufferedImage, "png", outputStream)
            val bytes = outputStream.toByteArray()

            Attachment(
                id = Uuid.random().toString(),
                filename = "clipboard_image.png",
                mimeType = "image/png",
                bytes = bytes,
                thumbnailBytes = bytes
            )
        } catch (e: Exception) {
            null
        }
    }
}
