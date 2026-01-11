package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.ServerProfile
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing multiple OpenCode backend servers and the active server selection.
 */
interface ServerRepository {
    /** Stream of all saved server profiles. */
    fun getServers(): Flow<List<ServerProfile>>

    /** Snapshot of all saved server profiles. */
    fun getServersSnapshot(): List<ServerProfile>

    /** Stream of the active server profile ID. */
    fun getActiveServerId(): Flow<String?>

    /** Snapshot of the active server profile ID. */
    fun getActiveServerIdSnapshot(): String?

    /** Snapshot of the active server profile (if any). */
    fun getActiveServerSnapshot(): ServerProfile?

    /**
     * Ensure server configuration is initialized.
     *
     * If no profiles exist, creates a default profile from a built-in base URL and marks it active.
     */
    suspend fun ensureInitialized(): Result<ServerProfile>

    /** Add a server profile. */
    suspend fun addServer(name: String, baseUrlInput: String): Result<ServerProfile>

    /** Update the active server selection. */
    suspend fun setActiveServer(serverId: String): Result<Unit>
}
