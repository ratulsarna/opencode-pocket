package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.AgentDto
import com.ratulsarna.ocmobile.data.dto.CommandInfoDto
import com.ratulsarna.ocmobile.data.dto.ConfigResponse
import com.ratulsarna.ocmobile.data.dto.CreateSessionRequest
import com.ratulsarna.ocmobile.data.dto.FileContentDto
import com.ratulsarna.ocmobile.data.dto.FileNodeDto
import com.ratulsarna.ocmobile.data.dto.FileStatusDto
import com.ratulsarna.ocmobile.data.dto.ForkSessionRequest
import com.ratulsarna.ocmobile.data.dto.MessageWithPartsDto
import com.ratulsarna.ocmobile.data.dto.PermissionReplyRequestDto
import com.ratulsarna.ocmobile.data.dto.PermissionRequestDto
import com.ratulsarna.ocmobile.data.dto.ProjectInfoDto
import com.ratulsarna.ocmobile.data.dto.ProviderListResponse
import com.ratulsarna.ocmobile.data.dto.RevertSessionRequest
import com.ratulsarna.ocmobile.data.dto.ServerPathDto
import com.ratulsarna.ocmobile.data.dto.SendCommandRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageResponse
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.settings.AppSettingsImpl
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceRepositoryTest {

    @Test
    fun WorkspaceRepository_activateWorkspace_updatesActiveWorkspaceAndLastUsed() = runTest {
        val appSettings = AppSettingsImpl(settings = MapSettings())
        appSettings.setActiveServerId("srvA")
        appSettings.setInstallationIdForServer("srvA", "inst1")

        val repo = com.ratulsarna.ocmobile.data.repository.WorkspaceRepositoryImpl(
            openCodeApi = ThrowingOpenCodeApi(),
            appSettings = appSettings,
            nowMs = { 123L }
        )

        val a = Workspace(projectId = "projA", worktree = "/repoA", name = "Repo A", lastUsedAtMs = null)
        val b = Workspace(projectId = "projB", worktree = "/repoB", name = "Repo B", lastUsedAtMs = null)
        appSettings.setWorkspacesForInstallation("inst1", listOf(a, b))

        repo.activateWorkspace(projectId = "projB").getOrThrow()

        val active = appSettings.getActiveWorkspaceSnapshot()
        assertNotNull(active)
        assertEquals("projB", active.projectId)
        assertEquals(123L, active.lastUsedAtMs)

        val stored = appSettings.getWorkspacesSnapshot()
        val storedB = stored.first { it.projectId == "projB" }
        assertEquals(123L, storedB.lastUsedAtMs)
    }

    @Test
    fun WorkspaceRepository_addWorkspace_preservesLastUsedAt_whenWorkspaceAlreadyExists() = runTest {
        val appSettings = AppSettingsImpl(settings = MapSettings())
        appSettings.setActiveServerId("srvA")
        appSettings.setInstallationIdForServer("srvA", "inst1")

        val repo = com.ratulsarna.ocmobile.data.repository.WorkspaceRepositoryImpl(
            openCodeApi = FixedProjectOpenCodeApi(),
            appSettings = appSettings,
            nowMs = { 123L }
        )

        val existing = Workspace(projectId = "projA", worktree = "/repoA", name = "Repo A", lastUsedAtMs = 999L)
        appSettings.setWorkspacesForInstallation("inst1", listOf(existing))

        repo.addWorkspace(directoryInput = "/repoA").getOrThrow()

        val stored = appSettings.getWorkspacesSnapshot()
        val storedA = stored.first { it.projectId == "projA" }
        assertEquals(999L, storedA.lastUsedAtMs)
    }

    private class ThrowingOpenCodeApi : OpenCodeApi {
        override suspend fun sendMessage(sessionId: String, request: SendMessageRequest): SendMessageResponse = TODO()
        override suspend fun getMessages(sessionId: String, limit: Int?, reverse: Boolean?): List<MessageWithPartsDto> = TODO()
        override suspend fun getSession(sessionId: String): SessionDto = TODO()
        override suspend fun getSessions(search: String?, limit: Int?, start: Long?): List<SessionDto> = TODO()
        override suspend fun createSession(request: CreateSessionRequest): SessionDto = TODO()
        override suspend fun forkSession(sessionId: String, request: ForkSessionRequest): SessionDto = TODO()
        override suspend fun revertSession(sessionId: String, request: RevertSessionRequest): SessionDto = TODO()
        override suspend fun getAgents(): List<AgentDto> = TODO()
        override suspend fun getProviders(): ProviderListResponse = TODO()
        override suspend fun getConfig(): ConfigResponse = TODO()
        override suspend fun getCurrentProject(directory: String?): ProjectInfoDto = TODO()
        override suspend fun getPath(): ServerPathDto = TODO()
        override suspend fun listProjects(): List<ProjectInfoDto> = TODO()
        override suspend fun getFileContent(path: String): FileContentDto = TODO()
        override suspend fun listFiles(path: String): List<FileNodeDto> = TODO()
        override suspend fun getFileStatus(): List<FileStatusDto> = TODO()
        override suspend fun abortSession(sessionId: String): Boolean = TODO()
        override suspend fun findVaultFiles(query: String, includeDirs: Boolean): List<String> = TODO()
        override suspend fun getPermissionRequests(): List<PermissionRequestDto> = TODO()
        override suspend fun replyToPermissionRequest(requestId: String, request: PermissionReplyRequestDto) = TODO()
        override suspend fun listCommands(): List<CommandInfoDto> = TODO()
        override suspend fun sendCommand(sessionId: String, request: SendCommandRequest): SendMessageResponse = TODO()
    }

    private class FixedProjectOpenCodeApi : OpenCodeApi {
        override suspend fun sendMessage(sessionId: String, request: SendMessageRequest): SendMessageResponse = TODO()
        override suspend fun getMessages(sessionId: String, limit: Int?, reverse: Boolean?): List<MessageWithPartsDto> = TODO()
        override suspend fun getSession(sessionId: String): SessionDto = TODO()
        override suspend fun getSessions(search: String?, limit: Int?, start: Long?): List<SessionDto> = TODO()
        override suspend fun createSession(request: CreateSessionRequest): SessionDto = TODO()
        override suspend fun forkSession(sessionId: String, request: ForkSessionRequest): SessionDto = TODO()
        override suspend fun revertSession(sessionId: String, request: RevertSessionRequest): SessionDto = TODO()
        override suspend fun getAgents(): List<AgentDto> = TODO()
        override suspend fun getProviders(): ProviderListResponse = TODO()
        override suspend fun getConfig(): ConfigResponse = TODO()
        override suspend fun getCurrentProject(directory: String?): ProjectInfoDto {
            return ProjectInfoDto(
                id = "projA",
                worktree = "/repoA",
                name = "Repo A"
            )
        }
        override suspend fun getPath(): ServerPathDto = TODO()
        override suspend fun listProjects(): List<ProjectInfoDto> = TODO()
        override suspend fun getFileContent(path: String): FileContentDto = TODO()
        override suspend fun listFiles(path: String): List<FileNodeDto> = TODO()
        override suspend fun getFileStatus(): List<FileStatusDto> = TODO()
        override suspend fun abortSession(sessionId: String): Boolean = TODO()
        override suspend fun findVaultFiles(query: String, includeDirs: Boolean): List<String> = TODO()
        override suspend fun getPermissionRequests(): List<PermissionRequestDto> = TODO()
        override suspend fun replyToPermissionRequest(requestId: String, request: PermissionReplyRequestDto) = TODO()
        override suspend fun listCommands(): List<CommandInfoDto> = TODO()
        override suspend fun sendCommand(sessionId: String, request: SendCommandRequest): SendMessageResponse = TODO()
    }
}
