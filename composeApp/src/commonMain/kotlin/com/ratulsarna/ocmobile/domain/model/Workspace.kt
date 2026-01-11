package com.ratulsarna.ocmobile.domain.model

/**
 * A workspace the user can select in the mobile app.
 *
 * In OpenCode terms, this corresponds to a server-side Project (keyed by projectId) and is scoped
 * to a project root directory (worktree).
 */
data class Workspace(
    val projectId: String,
    val worktree: String,
    val name: String? = null,
    val lastUsedAtMs: Long? = null
)

