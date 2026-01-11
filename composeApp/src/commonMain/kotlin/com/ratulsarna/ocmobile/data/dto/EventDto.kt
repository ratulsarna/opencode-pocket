package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wrapper for SSE event data.
 */
@Serializable
data class SseEventWrapper(
    val directory: String = "",
    val payload: JsonElement
)

/**
 * Payload for message.part.updated event.
 */
@Serializable
data class MessagePartUpdatedPayload(
    @SerialName("sessionID") val sessionId: String,
    @SerialName("messageID") val messageId: String,
    @SerialName("partIndex") val partIndex: Int,
    val part: PartDto,
    val delta: String? = null
)

/**
 * Payload for message.updated event.
 */
@Serializable
data class MessageUpdatedPayload(
    val info: MessageInfoDto
)

/**
 * Payload for message.removed event.
 */
@Serializable
data class MessageRemovedPayload(
    @SerialName("sessionID") val sessionId: String,
    @SerialName("messageID") val messageId: String
)

/**
 * Payload for session.created/updated/deleted events.
 */
@Serializable
data class SessionEventPayload(
    val info: SessionDto
)

/**
 * Payload for session.status event.
 * Status is an object with 'type' field, not a simple string.
 */
@Serializable
data class SessionStatusPayload(
    @SerialName("sessionID") val sessionId: String,
    val status: SessionStatusDto
)

/**
 * Session status from API - polymorphic based on 'type' field.
 */
@Serializable
data class SessionStatusDto(
    val type: String,
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
)
