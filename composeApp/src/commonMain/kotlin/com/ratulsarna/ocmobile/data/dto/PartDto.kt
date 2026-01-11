package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Generic part DTO that can represent any message part type.
 * Uses optional fields to handle polymorphic types without discriminator.
 */
@Serializable
data class PartDto(
    val id: String? = null,
    val type: String,
    // TextPart, ReasoningPart
    val text: String? = null,
    val synthetic: Boolean? = null,
    val ignored: Boolean? = null,
    val metadata: JsonElement? = null,
    // ToolPart - state is a complex object, not a string
    @SerialName("callID") val callId: String? = null,
    val tool: String? = null,
    val state: ToolStateDto? = null,
    // FilePart
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val source: JsonElement? = null,
    // StepStartPart, StepFinishPart, SnapshotPart
    val snapshot: String? = null,
    val reason: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
    // PatchPart
    val hash: String? = null,
    val files: List<String>? = null,
    // AgentPart
    val name: String? = null,
    // RetryPart
    val attempt: Int? = null,
    val error: ErrorDto? = null,
    val time: PartTimeDto? = null,
    // CompactionPart
    val auto: Boolean? = null,
    // SubtaskPart
    val prompt: String? = null,
    val description: String? = null,
    val agent: String? = null
)

/**
 * Tool state DTO - represents the polymorphic ToolState from API.
 * Status determines which fields are populated.
 */
@Serializable
data class ToolStateDto(
    val status: String,
    val input: JsonElement? = null,
    val output: String? = null,
    val error: String? = null,
    val title: String? = null,
    val raw: String? = null,
    val metadata: JsonElement? = null,
    val time: ToolTimeDto? = null,
    val attachments: List<PartDto>? = null
)

@Serializable
data class ToolTimeDto(
    val start: Long? = null,
    val end: Long? = null,
    val compacted: Long? = null
)

@Serializable
data class PartTimeDto(
    val start: Long? = null,
    val end: Long? = null,
    val created: Long? = null
)
