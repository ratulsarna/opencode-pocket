package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Error information from OpenCode API.
 */
@Serializable
data class ErrorDto(
    val name: String,
    val data: JsonElement? = null,
    val message: String? = null
)
