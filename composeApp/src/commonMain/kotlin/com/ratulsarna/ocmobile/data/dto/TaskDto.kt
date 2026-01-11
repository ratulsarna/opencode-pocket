package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val body: String? = null,
    val status: String,
    val priority: Int = 0,
    @SerialName("due_at_ms") val dueAtMs: Long? = null,
    @SerialName("planned_for_at_ms") val plannedForAtMs: Long? = null,
    @SerialName("sort_order") val sortOrder: Long? = null,
    @SerialName("source_key") val sourceKey: String? = null,
    val metadata: JsonElement? = null,
    @SerialName("created_at") val createdAtMs: Long,
    @SerialName("updated_at") val updatedAtMs: Long,
    @SerialName("completed_at") val completedAtMs: Long? = null,
    @SerialName("archived_at") val archivedAtMs: Long? = null,
    @SerialName("deleted_at") val deletedAtMs: Long? = null
)

@Serializable
data class TasksListResponse(
    val tasks: List<TaskDto>,
    val total: Int,
    val limit: Int = 50,
    val offset: Int = 0
)

@Serializable
data class TaskStatsResponse(
    val open: Int,
    val done: Int,
    val overdue: Int,
    val planned: Int? = null
)

@Serializable
data class DeleteTaskResponse(
    val status: String
)
