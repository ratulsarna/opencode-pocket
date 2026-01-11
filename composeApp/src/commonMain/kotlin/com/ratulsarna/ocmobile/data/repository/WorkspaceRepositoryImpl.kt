package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.model.InstallationId
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkspaceRepositoryImpl(
    private val openCodeApi: OpenCodeApi,
    private val appSettings: AppSettings,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : WorkspaceRepository {

    private val installationIdMutex = Mutex()

    override fun getWorkspaces(): Flow<List<Workspace>> = appSettings.getWorkspaces()

    override fun getActiveWorkspace(): Flow<Workspace?> = appSettings.getActiveWorkspace()

    override fun getActiveWorkspaceSnapshot(): Workspace? = appSettings.getActiveWorkspaceSnapshot()

    override suspend fun ensureInitialized(): Result<Workspace> {
        return runCatching {
            val serverId = appSettings.getActiveServerIdSnapshot()?.trim().takeIf { !it.isNullOrBlank() }
                ?: error("No active server selected")

            val installationId = ensureInstallationId(serverId)

            val existingActive = appSettings.getActiveWorkspaceSnapshot()
            if (existingActive != null) return@runCatching existingActive

            val now = nowMs()
            val currentProject = openCodeApi.getCurrentProject(directory = null)
            val seeded = Workspace(
                projectId = currentProject.id,
                worktree = currentProject.worktree,
                name = currentProject.name,
                lastUsedAtMs = now
            )

            val existing = appSettings.getWorkspacesSnapshot()
            val merged = mergeWorkspaces(existing, listOf(seeded))
            appSettings.setWorkspacesForInstallation(installationId, merged)
            appSettings.setActiveWorkspace(installationId, seeded)
            seeded
        }
    }

    override suspend fun refresh(): Result<Unit> {
        return runCatching {
            val serverId = appSettings.getActiveServerIdSnapshot()?.trim().takeIf { !it.isNullOrBlank() }
                ?: error("No active server selected")
            val installationId = ensureInstallationId(serverId)

            val existing = appSettings.getWorkspacesSnapshot()
            val existingById = existing.associateBy { it.projectId }

            val fromServer = openCodeApi.listProjects().map { project ->
                Workspace(
                    projectId = project.id,
                    worktree = project.worktree,
                    name = project.name,
                    lastUsedAtMs = existingById[project.id]?.lastUsedAtMs
                )
            }

            val merged = mergeWorkspaces(existing, fromServer)
            appSettings.setWorkspacesForInstallation(installationId, merged)
        }
    }

    override suspend fun addWorkspace(directoryInput: String): Result<Workspace> {
        return runCatching {
            val input = directoryInput.trim().takeIf { it.isNotBlank() } ?: error("Directory cannot be empty")
            val serverId = appSettings.getActiveServerIdSnapshot()?.trim().takeIf { !it.isNullOrBlank() }
                ?: error("No active server selected")
            val installationId = ensureInstallationId(serverId)

            val project = openCodeApi.getCurrentProject(directory = input)
            val workspace = Workspace(
                projectId = project.id,
                worktree = project.worktree,
                name = project.name,
                lastUsedAtMs = null
            )

            val existing = appSettings.getWorkspacesSnapshot()
            val merged = mergeWorkspaces(existing, listOf(workspace))
            appSettings.setWorkspacesForInstallation(installationId, merged)
            workspace
        }
    }

    override suspend fun activateWorkspace(projectId: String): Result<Unit> {
        return runCatching {
            val id = projectId.trim().takeIf { it.isNotBlank() } ?: error("Project id cannot be empty")
            val serverId = appSettings.getActiveServerIdSnapshot()?.trim().takeIf { !it.isNullOrBlank() }
                ?: error("No active server selected")
            val installationId = ensureInstallationId(serverId)

            var existing = appSettings.getWorkspacesSnapshot()
            var match = existing.firstOrNull { it.projectId == id }
            if (match == null) {
                refresh().getOrThrow()
                existing = appSettings.getWorkspacesSnapshot()
                match = existing.firstOrNull { it.projectId == id } ?: error("Workspace not found: $id")
            }

            val now = nowMs()
            val updated = match.copy(lastUsedAtMs = now)
            val next = mergeWorkspaces(existing, listOf(updated))

            appSettings.setWorkspacesForInstallation(installationId, next)
            appSettings.setActiveWorkspace(installationId, updated)
        }
    }

    private suspend fun ensureInstallationId(serverId: String): String {
        return installationIdMutex.withLock {
            val existing = appSettings.getInstallationIdForActiveServerSnapshot()?.trim()?.takeIf { it.isNotBlank() }
            if (existing != null) return@withLock existing

            val path = openCodeApi.getPath()
            val derived = InstallationId.from(statePath = path.state, configPath = path.config)
            appSettings.setInstallationIdForServer(serverId, derived)
            derived
        }
    }

    private fun mergeWorkspaces(existing: List<Workspace>, updates: List<Workspace>): List<Workspace> {
        val byId = existing.associateBy { it.projectId }.toMutableMap()
        for (workspace in updates) {
            val current = byId[workspace.projectId]
            byId[workspace.projectId] = if (current != null) {
                workspace.copy(lastUsedAtMs = workspace.lastUsedAtMs ?: current.lastUsedAtMs)
            } else {
                workspace
            }
        }

        return byId.values
            .sortedWith(
                compareByDescending<Workspace> { it.lastUsedAtMs ?: 0L }
                    .thenBy { it.name?.lowercase() ?: "" }
                    .thenBy { it.projectId }
            )
    }
}
