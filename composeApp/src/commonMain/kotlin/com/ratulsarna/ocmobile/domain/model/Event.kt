package com.ratulsarna.ocmobile.domain.model

import kotlinx.datetime.Instant

/**
 * Sealed class representing SSE events from the OpenCode server.
 */
sealed class Event {
    abstract val directory: String
}

/**
 * Event fired when a message part is created or updated.
 * Used for streaming text during response generation.
 */
data class MessagePartUpdatedEvent(
    override val directory: String,
    val sessionId: String,
    val messageId: String,
    val partIndex: Int,
    val part: MessagePart,
    val delta: String? = null  // Incremental text for streaming
) : Event()

/**
 * Event fired when a message is removed.
 */
data class MessageRemovedEvent(
    override val directory: String,
    val sessionId: String,
    val messageId: String
) : Event()

/**
 * Event fired when a message is created or updated (metadata only; no parts).
 * Used to learn the message role (user/assistant) so we don't render user messages
 * as assistant placeholders during streaming.
 */
data class MessageUpdatedEvent(
    override val directory: String,
    val sessionId: String,
    val messageId: String,
    val role: String,
    val createdAt: Instant
) : Event()

/**
 * Event fired when a session is created.
 */
data class SessionCreatedEvent(
    override val directory: String,
    val session: Session
) : Event()

/**
 * Event fired when a session is updated.
 */
data class SessionUpdatedEvent(
    override val directory: String,
    val session: Session
) : Event()

/**
 * Event fired when a session is deleted.
 */
data class SessionDeletedEvent(
    override val directory: String,
    val session: Session
) : Event()

/**
 * Event indicating session processing status.
 */
data class SessionStatusEvent(
    override val directory: String,
    val sessionId: String,
    val status: SessionStatus
) : Event()

/**
 * Event fired when a file watcher detects a change in the worktree.
 */
data class FileWatcherUpdatedEvent(
    override val directory: String,
    val file: String,
    val event: FileWatcherUpdateType
) : Event()

/**
 * Event fired when OpenCode needs the client to decide a permission prompt.
 * (OpenCode v1.1.1+: `permission.asked`)
 */
data class PermissionAskedEvent(
    override val directory: String,
    val requestId: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String>,
    val always: List<String> = emptyList(),
    val toolMessageId: String? = null,
    val toolCallId: String? = null
) : Event()

/**
 * Event fired when a permission request is replied to.
 * (OpenCode v1.1.1+: `permission.replied`)
 */
data class PermissionRepliedEvent(
    override val directory: String,
    val sessionId: String,
    val requestId: String,
    val reply: String
) : Event()

enum class FileWatcherUpdateType {
    ADD,
    CHANGE,
    UNLINK
}

/**
 * Session processing status.
 */
enum class SessionStatus {
    IDLE,
    RUNNING,
    ERROR
}

/**
 * Unknown event type - for forward compatibility.
 */
data class UnknownEvent(
    override val directory: String,
    val eventType: String,
    val rawPayload: String
) : Event()
