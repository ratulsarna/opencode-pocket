package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Session data from OpenCode API.
 */
@Serializable
data class SessionDto(
    val id: String,
    @SerialName("projectID") val projectId: String? = null,
    val directory: String,
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: SessionTimeDto,
    val revert: SessionRevertDto? = null
)

@Serializable
data class SessionRevertDto(
    @SerialName("messageID") val messageId: String,
    @SerialName("partID") val partId: String? = null,
    val snapshot: String? = null,
    val diff: String? = null
)

@Serializable
data class SessionTimeDto(
    val created: Long,
    val updated: Long,
    val compacting: Long? = null,
    val archived: Long? = null
)

/**
 * Request body for creating a session.
 */
@Serializable
data class CreateSessionRequest(
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null
)

/**
 * Request body for forking a session.
 */
@Serializable
data class ForkSessionRequest(
    @SerialName("messageID") val messageId: String? = null
)

/**
 * Request body for reverting a session to a specific message.
 * OpenCode stores a "revert pointer" on the session and hides messages after that point (and can later unrevert).
 * Messages are physically cleaned up later (e.g. when the session resumes processing).
 */
@Serializable
data class RevertSessionRequest(
    @SerialName("messageID") val messageId: String,
    @SerialName("partID") val partId: String? = null
)
