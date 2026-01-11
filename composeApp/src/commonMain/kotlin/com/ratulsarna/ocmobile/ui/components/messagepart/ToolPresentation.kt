package com.ratulsarna.ocmobile.ui.components.messagepart

/**
 * Presentation model for rendering tool calls in UI.
 *
 * This intentionally avoids exposing raw JSON strings to the UI.
 *
 * Note: iOS UIKit chat uses this presentation model today; Compose tool-call UI is still legacy (see #164).
 */
data class ToolPresentation(
    val title: String,
    val subtitle: String? = null,
    val blocks: List<ToolPresentationBlock> = emptyList()
)

sealed class ToolPresentationBlock {
    data class File(
        val label: String = "File",
        val path: String
    ) : ToolPresentationBlock()

    data class Code(
        val label: String,
        val text: ToolTextPreview
    ) : ToolPresentationBlock()

    data class Diff(
        val label: String = "Diff",
        val text: ToolTextPreview
    ) : ToolPresentationBlock()

    data class KeyValues(
        val label: String = "Parameters",
        val items: List<ToolKeyValue>
    ) : ToolPresentationBlock()

    data class Error(
        val message: String
    ) : ToolPresentationBlock()
}

data class ToolTextPreview(
    val previewText: String,
    val fullText: String,
    val isTruncated: Boolean
)

data class ToolKeyValue(
    val key: String,
    val value: String
)
