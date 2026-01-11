package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskEnvelopeDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val task: TaskDto
)

@Serializable
data class TaskDeletedEnvelopeDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("task_id") val taskId: String
)
