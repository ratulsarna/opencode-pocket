package com.ratulsarna.ocmobile.ui.screen.chat

import com.ratulsarna.ocmobile.domain.model.Message
import com.ratulsarna.ocmobile.domain.model.TextPart
import com.ratulsarna.ocmobile.domain.model.UserMessage

internal fun filterMessagesForRevert(
    messages: List<Message>,
    revertMessageId: String?
): List<Message> {
    if (revertMessageId.isNullOrBlank()) return messages

    return messages.filter { msg ->
        // Local optimistic IDs don't follow OpenCode's sortable message ID scheme.
        if (msg.id.startsWith("pending-")) return@filter true
        // Match OpenCode UI semantics: hide the boundary message (marker is shown at that point)
        // and everything after it.
        msg.id < revertMessageId
    }
}

/**
 * Best-effort reconstruction of the prompt to restore after a revert.
 * Mirrors OpenCode TUI behavior: use the (last) user message at/before the revert point,
 * and exclude synthetic hook-injected text.
 */
internal fun promptTextForRevert(
    messages: List<Message>,
    revertMessageId: String?
): String? {
    if (revertMessageId.isNullOrBlank()) return null

    // Prefer a stable, ordering-independent selection:
    // - If the boundary message is present, use the last user message at/before it by position.
    // - Fallback to ID comparison if the boundary isn't in the list.
    val boundaryIndex = messages.indexOfFirst { it.id == revertMessageId }
    val candidate = if (boundaryIndex >= 0) {
        (boundaryIndex downTo 0)
            .asSequence()
            .map { idx -> messages[idx] }
            .filterIsInstance<UserMessage>()
            .firstOrNull()
    } else {
        messages
            .filterIsInstance<UserMessage>()
            .filter { it.id < revertMessageId }
            .maxByOrNull { it.createdAt }
    } ?: return null

    val text = candidate.parts
        .filterIsInstance<TextPart>()
        .filterNot { it.synthetic }
        .joinToString(separator = "") { it.text }
        .trim()

    return text.takeIf { it.isNotBlank() }
}
