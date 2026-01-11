package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.domain.model.Attachment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.memcpy
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Top-level function for Swift interop.
 * Swift calls this as: ShareExtensionBridgeKt.consumePendingShare()
 */
fun consumePendingShare() = ShareExtensionBridge.consumePendingShare()

/**
 * Bridge to receive shared content from the iOS Share Extension.
 * Reads manifest and files from the App Group container.
 */
object ShareExtensionBridge {
    // IMPORTANT: Must match App Group ID in:
    // - iosApp/iosApp/iosApp.entitlements
    // - iosApp/OCMobileShareExtension/OCMobileShareExtension.entitlements
    // - iosApp/iosApp/Shared/AppGroupFileManager.swift
    private const val APP_GROUP_ID = "group.com.ratulsarna.ocmobile"
    private const val MANIFEST_FILENAME = "SharedManifest.json"

    // Dedicated scope for managed coroutines
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Mutex to serialize consumption (prevent concurrent file operations)
    private val consumeMutex = Mutex()

    // Use MutableStateFlow + null-after-handling for EXACTLY-ONCE delivery
    private val _pendingPayload = MutableStateFlow<SharePayload?>(null)
    val pendingPayload: StateFlow<SharePayload?> = _pendingPayload.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the manifest and files from the App Group container.
     * Called from Swift when the app is opened via URL scheme or on startup.
     */
    fun consumePendingShare() {
        scope.launch {
            consumeMutex.withLock {
                consumePendingShareInternal()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)
    private fun consumePendingShareInternal() {
        // 1. Get App Group container URL
        val containerUrl = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID)
            ?: return

        val containerPath = containerUrl.path ?: return

        // 2. Check if manifest exists (idempotent)
        val manifestPath = "$containerPath/$MANIFEST_FILENAME"
        if (!NSFileManager.defaultManager.fileExistsAtPath(manifestPath)) return

        // 3. Read manifest
        val manifestJson = NSString.stringWithContentsOfFile(
            manifestPath,
            encoding = NSUTF8StringEncoding,
            error = null
        ) ?: run {
            // Malformed manifest: cleanup and bail
            deleteManifestAndFiles(containerPath, null)
            return
        }

        val manifest = try {
            json.decodeFromString<SharedManifestDto>(manifestJson)
        } catch (e: Exception) {
            println("[ShareExtensionBridge] Failed to parse manifest: ${e.message}")
            // Malformed manifest: cleanup and bail
            deleteManifestAndFiles(containerPath, null)
            return
        }

        // 4. For each file reference, read bytes and create Attachment
        val attachments = manifest.files.mapNotNull { fileRef ->
            val filePath = "$containerPath/${fileRef.relativePath}"
            val data = NSFileManager.defaultManager.contentsAtPath(filePath)
                ?: return@mapNotNull null

            val bytes = data.toByteArray()
            val mimeType = fileRef.mimeType.ifEmpty { guessMimeType(fileRef.filename) }

            Attachment(
                id = fileRef.id.ifEmpty { Uuid.random().toString() },
                filename = fileRef.filename,
                mimeType = mimeType,
                bytes = bytes,
                thumbnailBytes = if (mimeType.startsWith("image/")) bytes else null
            )
        }

        // 5. Store payload for handler (StateFlow delivers to current/future collectors)
        if (attachments.isNotEmpty() || !manifest.text.isNullOrBlank()) {
            _pendingPayload.value = SharePayload(attachments, manifest.text)
        }

        // 6. Cleanup: delete per-share folder + manifest
        deleteManifestAndFiles(containerPath, manifest)
    }

    /**
     * Called by handler after processing to prevent re-delivery.
     */
    fun clearPendingPayload() {
        _pendingPayload.value = null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun deleteManifestAndFiles(containerPath: String, manifest: SharedManifestDto?) {
        val fileManager = NSFileManager.defaultManager

        // Delete manifest
        val manifestPath = "$containerPath/$MANIFEST_FILENAME"
        try {
            fileManager.removeItemAtPath(manifestPath, error = null)
        } catch (_: Exception) {}

        // Delete temp manifest if exists
        val tempManifestPath = "$containerPath/$MANIFEST_FILENAME.tmp"
        try {
            fileManager.removeItemAtPath(tempManifestPath, error = null)
        } catch (_: Exception) {}

        // Delete share folder if we have file references
        manifest?.files?.firstOrNull()?.relativePath?.let { firstPath ->
            // Extract folder: "Shares/uuid/file.png" -> "Shares/uuid"
            val parts = firstPath.split("/")
            if (parts.size >= 2) {
                val folderPath = "$containerPath/${parts[0]}/${parts[1]}"
                try {
                    fileManager.removeItemAtPath(folderPath, error = null)
                } catch (_: Exception) {}
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
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

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            else -> "application/octet-stream"
        }
    }
}

/**
 * Payload containing shared attachments and text.
 */
data class SharePayload(
    val attachments: List<Attachment>,
    val text: String?
)

/**
 * DTO for parsing the SharedManifest.json file.
 */
@Serializable
private data class SharedManifestDto(
    val files: List<SharedFileRefDto>,
    val text: String? = null,
    val timestampEpochMs: Long
)

@Serializable
private data class SharedFileRefDto(
    val id: String,
    val filename: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long
)
