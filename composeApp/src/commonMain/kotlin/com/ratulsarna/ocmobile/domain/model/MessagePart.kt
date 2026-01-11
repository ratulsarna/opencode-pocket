package com.ratulsarna.ocmobile.domain.model

import com.ratulsarna.ocmobile.domain.error.ApiError

/**
 * Sealed class representing different types of message parts.
 * OpenCode messages can contain multiple parts of various types.
 */
sealed class MessagePart {
    abstract val id: String?
}

/**
 * Plain text content.
 */
data class TextPart(
    override val id: String? = null,
    val text: String,
    val synthetic: Boolean = false
) : MessagePart()

/**
 * Reasoning/thinking content from the model.
 */
data class ReasoningPart(
    override val id: String? = null,
    val text: String
) : MessagePart()

/**
 * Tool call or tool result.
 */
data class ToolPart(
    override val id: String? = null,
    val callId: String,
    val tool: String,
    val state: ToolState,
    val input: String? = null,
    val output: String? = null,
    val error: String? = null,
    val title: String? = null,
    val metadata: String? = null,
    val time: ToolTime? = null,
    val attachments: List<MessagePart> = emptyList()
) : MessagePart()

/**
 * State of a tool execution.
 */
enum class ToolState {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR
}

data class ToolTime(
    val start: Long? = null,
    val end: Long? = null,
    val compacted: Long? = null
)

/**
 * File content part.
 */
data class FilePart(
    override val id: String? = null,
    val mime: String,
    val filename: String?,
    val url: String
) : MessagePart()

/**
 * Marks the start of a processing step.
 */
data class StepStartPart(
    override val id: String? = null,
    val snapshot: String? = null
) : MessagePart()

/**
 * Marks the end of a processing step.
 */
data class StepFinishPart(
    override val id: String? = null,
    val reason: String,
    val snapshot: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null
) : MessagePart()

/**
 * Snapshot of the current state.
 */
data class SnapshotPart(
    override val id: String? = null,
    val snapshot: String
) : MessagePart()

/**
 * Patch/diff information.
 */
data class PatchPart(
    override val id: String? = null,
    val hash: String,
    val files: List<String>
) : MessagePart()

/**
 * Agent delegation part.
 */
data class AgentPart(
    override val id: String? = null,
    val name: String
) : MessagePart()

/**
 * Retry information after an error.
 */
data class RetryPart(
    override val id: String? = null,
    val attempt: Int,
    val error: ApiError?
) : MessagePart()

/**
 * Context compaction event.
 */
data class CompactionPart(
    override val id: String? = null,
    val auto: Boolean
) : MessagePart()

/**
 * Unknown part type - for forward compatibility.
 */
data class UnknownPart(
    override val id: String? = null,
    val type: String,
    val rawData: String
) : MessagePart()
