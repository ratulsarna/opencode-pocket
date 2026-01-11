package com.ratulsarna.ocmobile.domain.model

/**
 * A pending permission prompt from OpenCode.
 */
data class PermissionRequest(
    val requestId: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val always: List<String> = emptyList(),
    val toolMessageId: String? = null,
    val toolCallId: String? = null
)

enum class PermissionReply {
    ONCE,
    ALWAYS,
    REJECT
}

