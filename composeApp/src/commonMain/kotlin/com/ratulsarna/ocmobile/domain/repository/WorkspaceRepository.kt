package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.Workspace
import kotlinx.coroutines.flow.Flow

interface WorkspaceRepository {
    fun getWorkspaces(): Flow<List<Workspace>>

    fun getActiveWorkspace(): Flow<Workspace?>

    fun getActiveWorkspaceSnapshot(): Workspace?

    /**
     * Ensure the workspace system is initialized for the active server.
     *
     * This establishes the serverId -> installationId mapping and seeds an active workspace when
     * missing by using the server's current project.
     */
    suspend fun ensureInitialized(): Result<Workspace>

    /**
     * Refresh the list of known workspaces (projects) from the server.
     */
    suspend fun refresh(): Result<Unit>

    /**
     * Add a workspace by providing a directory path on the server machine.
     *
     * This resolves the directory to an OpenCode project, adds it to the workspace list, and
     * returns the resulting workspace (does not automatically activate it).
     */
    suspend fun addWorkspace(directoryInput: String): Result<Workspace>

    /**
     * Activate a workspace by project id, updating last-used timestamps.
     */
    suspend fun activateWorkspace(projectId: String): Result<Unit>
}

