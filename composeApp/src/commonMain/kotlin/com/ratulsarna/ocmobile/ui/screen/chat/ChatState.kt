package com.ratulsarna.ocmobile.ui.screen.chat

import com.ratulsarna.ocmobile.domain.model.AssistantResponsePartVisibility
import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilityPreset
import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilitySettings
import com.ratulsarna.ocmobile.domain.model.Attachment
import com.ratulsarna.ocmobile.domain.model.AttachmentError
import com.ratulsarna.ocmobile.domain.model.Message
import com.ratulsarna.ocmobile.domain.model.PermissionRequest
import com.ratulsarna.ocmobile.domain.model.VaultEntry

/**
 * UI state for the chat screen.
 */
data class ChatUiState(
    val currentSessionId: String? = null,
    /** If set, the session is in "reverted" state and messages after this point should be hidden. */
    val revertMessageId: String? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val isAborting: Boolean = false,
    val isReverting: Boolean = false,
    val sessionStatus: SessionUiStatus = SessionUiStatus.Idle,
    val connectionState: ChatConnectionState = ChatConnectionState.Connected,
    val streamingMessageId: String? = null,
    val lastGoodMessageId: String? = null,
    val error: ChatError? = null,
    // Input state (controlled by ViewModel for share extension prefill)
    val inputText: String = "",
    /** Cursor position in inputText. Used by ChatInput to position cursor after VM-driven text changes. */
    val inputCursor: Int = 0,
    // Attachment state
    val pendingAttachments: List<Attachment> = emptyList(),
    val hasClipboardImage: Boolean = false,
    val attachmentError: AttachmentError? = null,
    // Message action state (for long-press popup)
    val selectedMessageForAction: Message? = null,
    val showRevertConfirmation: Boolean = false,
    // Pending OpenCode permission prompt (tool execution, etc.)
    val pendingPermission: PermissionRequest? = null,
    // Mention autocomplete state (for @ vault file search)
    val mentionState: MentionState = MentionState.Inactive,
    // Slash command autocomplete state (for /command search)
    val slashCommandState: SlashCommandState = SlashCommandState.Inactive,
    // Developer toggles
    val alwaysExpandAssistantParts: Boolean = false,
    val assistantResponseVisibilityPreset: AssistantResponseVisibilityPreset = AssistantResponseVisibilityPreset.DEFAULT,
    val assistantResponsePartVisibility: AssistantResponsePartVisibility = AssistantResponseVisibilitySettings().effective(),
    /**
     * Available thinking/variant presets for the currently selected model.
     * When empty, the UI should not offer thinking level selection.
     */
    val thinkingVariants: List<String> = emptyList(),
    /**
     * Per-model override for thinking/variant (e.g., "high", "max").
     * Null means Auto (do not send a variant override).
     */
    val thinkingVariant: String? = null
)

/**
 * Session status for UI display.
 */
enum class SessionUiStatus {
    Idle,
    Processing,
    Error
}

/**
 * Connection state for UI display.
 */
enum class ChatConnectionState {
    Connected,
    Disconnected,
    Reconnecting
}

/**
 * Sealed class representing chat errors.
 */
sealed class ChatError {
    abstract val message: String?

    data class ConnectionFailed(override val message: String?) : ChatError()
    data class Unauthorized(override val message: String?) : ChatError()
    data class LoadFailed(override val message: String?) : ChatError()
    data class SendFailed(override val message: String?) : ChatError()
    data class StreamDisconnected(override val message: String?) : ChatError()
    data class SessionCorrupted(override val message: String?) : ChatError()
    data class RevertFailed(override val message: String?) : ChatError()
    data class ForkFailed(override val message: String?) : ChatError()
    data class AbortFailed(override val message: String?) : ChatError()
    data class PermissionReplyFailed(override val message: String?) : ChatError()
}

/**
 * State for "@" mention autocomplete feature.
 * Tracks whether a mention is active and its search results.
 */
sealed class MentionState {
    /** No active mention - default state */
    data object Inactive : MentionState()

    /**
     * A mention is active and possibly searching.
     * @param mentionStartIndex Index in the text where "@" was typed
     * @param query Text after "@" (e.g., "proj" for "@proj")
     * @param selectedIndex Which suggestion is currently highlighted (for keyboard up/down navigation)
     * @param isLoading Whether we're currently searching
     * @param suggestions Matching vault entries
     * @param error Error message if search failed
     */
    data class Active(
        val mentionStartIndex: Int,
        val query: String,
        val selectedIndex: Int = 0,
        val isLoading: Boolean = false,
        val suggestions: List<VaultEntry> = emptyList(),
        val error: String? = null
    ) : MentionState()
}

/**
 * State for "/" slash command autocomplete feature.
 * Tracks whether a slash command popup is active and its suggestions.
 */
sealed class SlashCommandState {
    /** No active slash command popup - default state */
    data object Inactive : SlashCommandState()

    /**
     * Slash command popup is active.
     * @param commandStartIndex Index in the text where "/" was typed (typically 0)
     * @param query Text after "/" up to cursor (can be empty for showing all commands)
     * @param selectedIndex Which suggestion is currently highlighted
     * @param isLoading Whether we're currently loading commands list
     * @param suggestions Matching commands
     * @param error Error message if loading failed
     */
    data class Active(
        val commandStartIndex: Int,
        val query: String,
        val selectedIndex: Int = 0,
        val isLoading: Boolean = false,
        val suggestions: List<com.ratulsarna.ocmobile.domain.model.CommandInfo> = emptyList(),
        val error: String? = null
    ) : SlashCommandState()
}
