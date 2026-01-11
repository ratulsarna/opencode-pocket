package com.ratulsarna.ocmobile.platform

import com.ratulsarna.ocmobile.domain.model.Attachment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard
import platform.posix.memcpy
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)
actual open class ClipboardImageReader actual constructor() {

    actual open suspend fun hasImage(): Boolean {
        return UIPasteboard.generalPasteboard.hasImages
    }

    actual open suspend fun readImage(): Attachment? {
        // UIKit clipboard access must happen on Main thread
        val image = withContext(Dispatchers.Main) {
            UIPasteboard.generalPasteboard.image
        } ?: return null

        // Move CPU-intensive image encoding to Default dispatcher
        return withContext(Dispatchers.Default) {
            // Try PNG first (preserves transparency), fall back to JPEG
            val (data, mimeType) = UIImagePNGRepresentation(image)?.let { it to "image/png" }
                ?: UIImageJPEGRepresentation(image, 0.9)?.let { it to "image/jpeg" }
                ?: return@withContext null

            val bytes = data.toByteArray()

            Attachment(
                id = Uuid.random().toString(),
                filename = "clipboard_image.${if (mimeType == "image/png") "png" else "jpg"}",
                mimeType = mimeType,
                bytes = bytes,
                thumbnailBytes = bytes  // Full image serves as thumbnail for clipboard
            )
        }
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) {
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), this.bytes, length)
            }
        }
        return bytes
    }
}
