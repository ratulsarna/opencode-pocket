package com.ratulsarna.ocmobile.domain.model

import kotlinx.datetime.Instant

/**
 * Domain model representing an OpenCode session.
 */
data class Session(
    val id: String,
    val directory: String,
    val title: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val parentId: String? = null,
    val revert: SessionRevert? = null
)

data class SessionRevert(
    val messageId: String,
    val partId: String? = null,
    val diff: String? = null
)
