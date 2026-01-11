package com.ratulsarna.ocmobile.platform

import com.ratulsarna.ocmobile.domain.model.Attachment

/**
 * Platform-specific clipboard image reading.
 * Allows reading images from the system clipboard.
 */
expect open class ClipboardImageReader() {
    /**
     * Check if clipboard currently contains an image.
     */
    open suspend fun hasImage(): Boolean

    /**
     * Read image from clipboard as an Attachment.
     * Returns null if no image is available.
     */
    open suspend fun readImage(): Attachment?
}
