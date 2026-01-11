package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable

/**
 * Response for OpenCode `GET /path`.
 */
@Serializable
data class ServerPathDto(
    val home: String,
    val state: String,
    val config: String,
    val worktree: String,
    val directory: String
)

