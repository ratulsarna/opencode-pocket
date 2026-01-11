package com.ratulsarna.ocmobile.domain.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Represents a file attachment pending upload.
 * Used in UI state before sending.
 */
data class Attachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
    val thumbnailBytes: ByteArray? = null
) {
    val sizeBytes: Int get() = bytes.size

    val isImage: Boolean get() = mimeType.startsWith("image/")

    /**
     * Convert attachment bytes to a data URL for API transmission.
     * Format: data:mime/type;base64,<encoded-data>
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toDataUrl(): String {
        val base64 = Base64.encode(bytes)
        return "data:$mimeType;base64,$base64"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attachment) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Validation errors for attachments.
 */
sealed class AttachmentError {
    data class FileTooLarge(
        val filename: String,
        val sizeBytes: Long,
        val maxBytes: Long
    ) : AttachmentError()

    data class TooManyFiles(
        val count: Int,
        val max: Int
    ) : AttachmentError()

    data class UnsupportedType(
        val filename: String,
        val mimeType: String
    ) : AttachmentError()

    /** User tapped "Paste from Clipboard" but no image was available. */
    data object NoClipboardImage : AttachmentError()
}

/**
 * Result of attempting to create an attachment from a file.
 */
sealed class AttachmentResult {
    data class Success(val attachment: Attachment) : AttachmentResult()
    data class Error(val error: AttachmentError) : AttachmentResult()
}

/**
 * Constants for attachment validation.
 */
object AttachmentLimits {
    const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L  // 10MB
    const val MAX_FILES_PER_MESSAGE = 5

    val SUPPORTED_IMAGE_TYPES = setOf(
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/jpg"
    )
    val SUPPORTED_DOCUMENT_TYPES = setOf(
        "application/pdf", "text/plain", "text/markdown"
    )
    val ALL_SUPPORTED_TYPES = SUPPORTED_IMAGE_TYPES + SUPPORTED_DOCUMENT_TYPES

    /**
     * Validate an attachment against limits.
     * Returns null if valid, or an AttachmentError if invalid.
     */
    fun validate(attachment: Attachment, currentCount: Int): AttachmentError? {
        if (currentCount >= MAX_FILES_PER_MESSAGE) {
            return AttachmentError.TooManyFiles(
                count = currentCount + 1,
                max = MAX_FILES_PER_MESSAGE
            )
        }
        if (attachment.sizeBytes > MAX_FILE_SIZE_BYTES) {
            return AttachmentError.FileTooLarge(
                filename = attachment.filename,
                sizeBytes = attachment.sizeBytes.toLong(),
                maxBytes = MAX_FILE_SIZE_BYTES
            )
        }
        // Note: We allow any mime type for now, but this could be restricted
        return null
    }
}

/**
 * Sources from which attachments can be added.
 */
enum class AttachmentSource {
    PHOTOS,
    FILES,
    CLIPBOARD
}
