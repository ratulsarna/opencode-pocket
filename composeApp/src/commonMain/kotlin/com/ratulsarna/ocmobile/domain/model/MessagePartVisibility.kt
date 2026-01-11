package com.ratulsarna.ocmobile.domain.model

/**
 * Filters message parts for display.
 *
 * Notes:
 * - `TextPart` is included when non-empty (including synthetic).
 * - `FilePart` is always included (attachments are part of the substantive message).
 * - Step markers and snapshots are always excluded to preserve current UI behavior.
 */
fun filterVisibleParts(
    parts: List<MessagePart>,
    visibility: AssistantResponsePartVisibility
): List<MessagePart> {
    return parts.filter { part ->
        when (part) {
            is TextPart -> part.text.isNotEmpty()
            is FilePart -> true
            is ReasoningPart -> visibility.showReasoning && part.text.isNotEmpty()
            is ToolPart -> visibility.showTools && part.tool.isNotEmpty()
            is PatchPart -> visibility.showPatches
            is AgentPart -> visibility.showAgents
            is RetryPart -> visibility.showRetries
            is CompactionPart -> visibility.showCompactions
            is UnknownPart -> visibility.showUnknowns
            is StepStartPart, is StepFinishPart, is SnapshotPart -> false
        }
    }
}
