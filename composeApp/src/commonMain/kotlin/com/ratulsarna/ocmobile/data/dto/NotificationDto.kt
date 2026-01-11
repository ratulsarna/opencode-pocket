package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// === REST API DTOs ===

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("read_at") val readAt: Long? = null,
    @SerialName("archived_at") val archivedAt: Long? = null,
    val metadata: NotificationMetadataDto? = null
)

@Serializable
data class NotificationMetadataDto(
    @SerialName("meeting_id") val meetingId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("deep_link_path") val deepLinkPath: String? = null,
    @SerialName("task_id") val taskId: String? = null
)

@Serializable
data class NotificationsListResponse(
    val notifications: List<NotificationDto>,
    val total: Int,
    val limit: Int = 50,
    val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean? = null
) {
    val computedHasMore: Boolean
        get() = hasMore ?: (offset + notifications.size < total)
}

@Serializable
data class UnreadCountResponse(
    val count: Int? = null,
    @SerialName("unread_count") val unreadCount: Int? = null
) {
    val value: Int
        get() = count ?: unreadCount ?: 0
}

@Serializable
data class MarkNotificationRequest(
    val read: Boolean? = null,
    val archived: Boolean? = null
)

// === SSE Envelope DTOs ===
// Backend wraps SSE payloads in envelopes with schema_version

@Serializable
data class NotificationCreatedEnvelopeDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val notification: NotificationDto
)

/**
 * notification.updated uses nested notification:{} (matching backend shape).
 * Can be partial - only id + changed fields.
 */
@Serializable
data class NotificationPatchedEnvelopeDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val notification: PartialNotificationDto
)

@Serializable
data class PartialNotificationDto(
    val id: String,
    @SerialName("read_at") val readAt: Long? = null,
    @SerialName("archived_at") val archivedAt: Long? = null
)

@Serializable
data class ProactiveEventEnvelopeDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("event_id") val eventId: String,
    val type: String,  // "proactive_outreach"
    @SerialName("session_id") val sessionId: String,
    val title: String,
    val body: String,
    @SerialName("deep_link") val deepLink: String = "oc-pocket://chat"
)
