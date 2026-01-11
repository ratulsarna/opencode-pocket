package com.ratulsarna.ocmobile.ui.screen.chat

import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilityPreset

/**
 * Single source of truth for whether the chat UI should show an "Assistant is typing" indicator.
 *
 * Product rules:
 * - Only show while a response is in progress.
 * - Only show for presets: TEXT_ONLY, TEXT_AND_THINKING.
 * - Never show for DEFAULT, ALL, or CUSTOM.
 */
fun shouldShowTypingIndicator(state: ChatUiState): Boolean {
    val responseInProgress =
        state.sessionStatus == SessionUiStatus.Processing || state.streamingMessageId != null
    if (!responseInProgress) return false

    return when (state.assistantResponseVisibilityPreset) {
        AssistantResponseVisibilityPreset.TEXT_ONLY,
        AssistantResponseVisibilityPreset.TEXT_AND_THINKING -> true

        AssistantResponseVisibilityPreset.DEFAULT,
        AssistantResponseVisibilityPreset.ALL,
        AssistantResponseVisibilityPreset.CUSTOM -> false
    }
}
