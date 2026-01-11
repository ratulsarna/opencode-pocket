package com.ratulsarna.ocmobile.domain.model

/**
 * A saved OpenCode backend profile (name + base URL).
 *
 * The iOS app can store multiple server profiles and switch between them.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long? = null
)

