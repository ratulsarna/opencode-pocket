package com.ratulsarna.ocmobile.domain.model

/**
 * A vault search result entry returned by OpenCode /find/file.
 *
 * The server can return both files and directories. We need to preserve that type so the UI can
 * render appropriately and ChatViewModel can generate correct message suffixes.
 */
data class VaultEntry(
    /** Path relative to vault root (normalized: no trailing slash). */
    val path: String,
    val type: VaultEntryType
) {
    val isDirectory: Boolean get() = type == VaultEntryType.DIRECTORY
    val isFile: Boolean get() = type == VaultEntryType.FILE
}

enum class VaultEntryType {
    FILE,
    DIRECTORY
}

