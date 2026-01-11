package com.ratulsarna.ocmobile.domain.model

import com.ratulsarna.ocmobile.domain.error.ApiError
import kotlinx.datetime.Instant

/**
 * Sealed class representing a chat message.
 */
sealed class Message {
    abstract val id: String
    abstract val sessionId: String
    abstract val createdAt: Instant
    abstract val parts: List<MessagePart>
}

/**
 * A message sent by the user.
 */
data class UserMessage(
    override val id: String,
    override val sessionId: String,
    override val createdAt: Instant,
    override val parts: List<MessagePart>
) : Message()

/**
 * A message from the assistant.
 */
data class AssistantMessage(
    override val id: String,
    override val sessionId: String,
    override val createdAt: Instant,
    override val parts: List<MessagePart>,
    val completedAt: Instant? = null,
    val error: ApiError? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
    val finishReason: String? = null,
    val providerId: String? = null,
    val modelId: String? = null
) : Message()

/**
 * Token usage statistics for an assistant message.
 */
data class TokenUsage(
    val input: Int,
    val output: Int,
    val reasoning: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0
)
