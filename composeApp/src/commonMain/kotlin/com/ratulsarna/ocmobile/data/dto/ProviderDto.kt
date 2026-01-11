package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Response from GET /provider endpoint.
 */
@Serializable
data class ProviderListResponse(
    val all: List<ProviderDto>,
    val connected: List<String>,
    val default: Map<String, String> = emptyMap()
)

/**
 * DTO for provider data from the API.
 */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String,
    val models: Map<String, ModelDto> = emptyMap()
)

/**
 * DTO for model data from the API.
 * Only includes fields we need; other fields are ignored by kotlinx.serialization.
 */
@Serializable
data class ModelDto(
    val id: String,
    val name: String,
    val limit: ModelLimitDto? = null,
    val capabilities: ModelCapabilitiesDto? = null,
    /**
     * Server-computed variants/options (e.g. reasoning effort presets).
     * We only need the keys; values are provider-specific option blobs.
     */
    val variants: Map<String, JsonElement>? = null
)

@Serializable
data class ModelCapabilitiesDto(
    val reasoning: Boolean? = null
)

/**
 * DTO for model context/output limits.
 */
@Serializable
data class ModelLimitDto(
    val context: Int? = null,
    val output: Int? = null
)
