package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenCode permission request (v1.1.1+).
 *
 * Emitted via SSE `permission.asked` and returned from `GET /permission`.
 */
@Serializable
data class PermissionRequestDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: JsonElement? = null,
    val always: List<String> = emptyList(),
    val tool: PermissionToolRefDto? = null
)

@Serializable
data class PermissionToolRefDto(
    @SerialName("messageID") val messageId: String,
    @SerialName("callID") val callId: String
)

/**
 * Body for `POST /permission/:requestID/reply`.
 */
@Serializable
data class PermissionReplyRequestDto(
    val reply: String,
    val message: String? = null
)

/**
 * SSE payload for `permission.replied` (v1.1.1+).
 */
@Serializable
data class PermissionRepliedPayloadDto(
    @SerialName("sessionID") val sessionId: String,
    @SerialName("requestID") val requestId: String,
    val reply: String
)

