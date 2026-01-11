package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable

/**
 * OpenCode Project info.
 *
 * This mirrors the server `Project.Info` shape returned by:
 * - `GET /project`
 * - `GET /project/current`
 */
@Serializable
data class ProjectInfoDto(
    val id: String,
    val worktree: String,
    val vcs: String? = null,
    val name: String? = null,
    val icon: ProjectIconDto? = null,
    val time: ProjectTimeDto? = null,
    val sandboxes: List<String> = emptyList()
)

@Serializable
data class ProjectIconDto(
    val url: String? = null,
    val color: String? = null
)

@Serializable
data class ProjectTimeDto(
    val created: Long,
    // Some OpenCode deployments omit `updated` for newly discovered projects.
    // Default to `created` so decoding remains backward compatible.
    val updated: Long = created,
    val initialized: Long? = null
)
