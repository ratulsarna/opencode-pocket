package com.ratulsarna.ocmobile.data.repository

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
import com.ratulsarna.ocmobile.data.dto.ModelDto
import com.ratulsarna.ocmobile.data.dto.PermissionReplyRequestDto
import com.ratulsarna.ocmobile.data.dto.PermissionRequestDto
import com.ratulsarna.ocmobile.data.dto.ProjectInfoDto
import com.ratulsarna.ocmobile.data.dto.ProviderDto
import com.ratulsarna.ocmobile.data.dto.ProviderListResponse
import com.ratulsarna.ocmobile.data.dto.RevertSessionRequest
import com.ratulsarna.ocmobile.data.dto.SendCommandRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageResponse
import com.ratulsarna.ocmobile.data.dto.ServerPathDto
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.settings.AppSettingsImpl
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryDefaultSelectionTest {

    @Test
    fun ModelRepository_selectsFirstAvailableModel_whenServerConfigMissing() = runTest {
        val settings = AppSettingsImpl(settings = MapSettings())
        val api = ProvidersOnlyApi(
            configModel = null,
            connectedProviders = listOf(
                ProviderDto(
                    id = "anthropic",
                    name = "Anthropic",
                    models = mapOf(
                        "claude" to ModelDto(id = "claude", name = "Claude"),
                        "opus" to ModelDto(id = "opus", name = "Opus")
                    )
                )
            )
        )

        val repo = ModelRepositoryImpl(api = api, appSettings = settings)
        repo.getConnectedProviders().getOrThrow()

        assertEquals(SelectedModel(providerId = "anthropic", modelId = "claude"), settings.getSelectedModelSnapshot())
    }

    @Test
    fun ModelRepository_selectsServerDefaultModel_whenAvailable() = runTest {
        val settings = AppSettingsImpl(settings = MapSettings())
        val api = ProvidersOnlyApi(
            configModel = "anthropic/opus",
            connectedProviders = listOf(
                ProviderDto(
                    id = "anthropic",
                    name = "Anthropic",
                    models = mapOf(
                        "claude" to ModelDto(id = "claude", name = "Claude"),
                        "opus" to ModelDto(id = "opus", name = "Opus")
                    )
                )
            )
        )

        val repo = ModelRepositoryImpl(api = api, appSettings = settings)
        repo.getConnectedProviders().getOrThrow()

        assertEquals(SelectedModel(providerId = "anthropic", modelId = "opus"), settings.getSelectedModelSnapshot())
    }

    private class ProvidersOnlyApi(
        private val configModel: String?,
        private val connectedProviders: List<ProviderDto>
    ) : OpenCodeApi {

        override suspend fun getProviders(): ProviderListResponse {
            return ProviderListResponse(
                all = connectedProviders,
                connected = connectedProviders.map { it.id }
            )
        }

        override suspend fun getConfig(): ConfigResponse {
            return ConfigResponse(model = configModel)
        }

        override suspend fun sendMessage(sessionId: String, request: SendMessageRequest): SendMessageResponse = TODO()
        override suspend fun getMessages(sessionId: String, limit: Int?, reverse: Boolean?): List<MessageWithPartsDto> = TODO()
        override suspend fun getSession(sessionId: String): SessionDto = TODO()
        override suspend fun getSessions(search: String?, limit: Int?, start: Long?): List<SessionDto> = TODO()
        override suspend fun createSession(request: CreateSessionRequest): SessionDto = TODO()
        override suspend fun forkSession(sessionId: String, request: ForkSessionRequest): SessionDto = TODO()
        override suspend fun revertSession(sessionId: String, request: RevertSessionRequest): SessionDto = TODO()
        override suspend fun getAgents(): List<AgentDto> = TODO()
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
}

