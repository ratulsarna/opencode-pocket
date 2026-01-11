package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request body for sending a message.
 */
@Serializable
data class SendMessageRequest(
    val parts: List<MessagePartRequest>,
    val model: ModelRequest? = null,
    val system: String? = null,
    val permission: JsonElement? = null,
    val tools: Map<String, Boolean>? = null,
    @SerialName("messageID") val messageId: String? = null,
    val agent: String? = null,
    val variant: String? = null,
    val noReply: Boolean? = null
)

/**
 * Message part for sending. Supports both text and file parts.
 * For text parts: type="text", text=content
 * For file parts: type="file", mime=mimeType, filename=name, url=data:mime;base64,...
 */
@Serializable
data class MessagePartRequest(
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null
)

@Serializable
data class ModelRequest(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String
)

/**
 * Response from sending a message.
 */
@Serializable
data class SendMessageResponse(
    val info: AssistantMessageInfoDto,
    val parts: List<PartDto>
)

/**
 * Assistant message metadata.
 */
@Serializable
data class AssistantMessageInfoDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val role: String,
    val time: MessageTimeDto,
    val error: ErrorDto? = null,
    @SerialName("parentID") val parentId: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val mode: String? = null,
    val path: PathDto? = null,
    val summary: Boolean? = null,
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
    val finish: String? = null
)

@Serializable
data class MessageTimeDto(
    val created: Long,
    val completed: Long? = null
)

@Serializable
data class PathDto(
    val cwd: String,
    val root: String
)

@Serializable
data class TokenUsageDto(
    val input: Int,
    val output: Int,
    val reasoning: Int = 0,
    val cache: CacheTokensDto? = null
)

@Serializable
data class CacheTokensDto(
    val read: Int,
    val write: Int
)

/**
 * Message with parts for list responses.
 * API returns { "info": {...}, "parts": [...] } structure.
 */
@Serializable
data class MessageWithPartsDto(
    val info: MessageInfoDto,
    val parts: List<PartDto> = emptyList()
)

/**
 * Message metadata (nested inside "info" in API responses).
 */
@Serializable
data class MessageInfoDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val role: String,
    val time: MessageTimeDto,
    val error: ErrorDto? = null,
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
    val finish: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val mode: String? = null,
    val path: PathDto? = null,
    // summary can be Boolean (assistant) or Object (user with title/body/diffs)
    val summary: JsonElement? = null,
    // User message specific fields
    val agent: String? = null,
    val model: ModelRequest? = null,
    val system: String? = null,
    val permission: JsonElement? = null,
    val tools: Map<String, Boolean>? = null
)
