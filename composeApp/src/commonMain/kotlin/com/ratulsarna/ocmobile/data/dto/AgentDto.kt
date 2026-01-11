package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable

/**
 * DTO for agent data from the API.
 */
@Serializable
data class AgentDto(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val builtIn: Boolean = false
)
