package com.ratulsarna.ocmobile.ui.screen.chat

import com.ratulsarna.ocmobile.data.mock.MockAppSettings
import com.ratulsarna.ocmobile.domain.model.Agent
import com.ratulsarna.ocmobile.domain.model.AgentMode
import com.ratulsarna.ocmobile.domain.model.AssistantMessage
import com.ratulsarna.ocmobile.domain.model.ContextUsage
import com.ratulsarna.ocmobile.domain.model.ServerProfile
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.domain.repository.AgentRepository
import com.ratulsarna.ocmobile.domain.repository.ConnectionState
import com.ratulsarna.ocmobile.domain.repository.ContextUsageRepository
import com.ratulsarna.ocmobile.domain.repository.EventStream
import com.ratulsarna.ocmobile.domain.repository.MessageRepository
import com.ratulsarna.ocmobile.domain.repository.ModelRepository
import com.ratulsarna.ocmobile.domain.repository.ModelSpec
import com.ratulsarna.ocmobile.domain.repository.PermissionRepository
import com.ratulsarna.ocmobile.domain.repository.ServerRepository
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.platform.ClipboardImageReader
import com.ratulsarna.ocmobile.testing.MainDispatcherRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSendDefaultsTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun ChatViewModel_sendMessage_initializesDefaultAgentAndModelBeforeSending() = runTest(dispatcher) {
        val appSettings = MockAppSettings()
        appSettings.setCurrentSessionId("ses-1")

        val workspaceGate = CompletableDeferred<Unit>()
        workspaceGate.complete(Unit)

        val captures = Captures()

        val vm = ChatViewModel(
            sessionRepository = FixedSessionRepository(),
            messageRepository = CapturingMessageRepository(captures),
            eventStream = SendDefaultsEventStream(),
            agentRepository = SeedingAgentRepository(appSettings),
            modelRepository = SeedingModelRepository(appSettings),
            clipboardImageReader = ClipboardImageReader(),
            contextUsageRepository = SendDefaultsContextUsageRepository(),
            appSettings = appSettings,
            permissionRepository = SendDefaultsPermissionRepository(),
            serverRepository = SendDefaultsServerRepository(),
            workspaceRepository = SendDefaultsWorkspaceRepository(workspaceGate),
            clipboardPollingIntervalMs = 0L
        )

        advanceUntilIdle()
        assertEquals("ses-1", vm.uiState.value.currentSessionId)

        vm.sendMessage("Hello")
        advanceUntilIdle()

        assertEquals("default", captures.agent)
        assertNotNull(captures.model)
        assertEquals(ModelSpec(providerId = "anthropic", modelId = "claude"), captures.model)
    }
}

private data class Captures(
    var agent: String? = null,
    var model: ModelSpec? = null
)

private class FixedSessionRepository : SessionRepository {
    override suspend fun getCurrentSessionId(): Result<String> = Result.success("ses-1")

    override suspend fun getSession(sessionId: String): Result<Session> = Result.success(
        Session(
            id = sessionId,
            directory = "",
            title = null,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            parentId = null,
            revert = null
        )
    )

    override suspend fun getSessions(search: String?, limit: Int?, start: Long?): Result<List<Session>> =
        Result.success(emptyList())

    override suspend fun createSession(title: String?, parentId: String?): Result<Session> =
        Result.failure(UnsupportedOperationException())

    override suspend fun forkSession(sessionId: String, messageId: String?): Result<Session> =
        Result.failure(UnsupportedOperationException())

    override suspend fun revertSession(sessionId: String, messageId: String): Result<Session> =
        Result.failure(UnsupportedOperationException())

    override suspend fun updateCurrentSessionId(sessionId: String): Result<Unit> = Result.success(Unit)

    override suspend fun abortSession(sessionId: String): Result<Boolean> = Result.success(true)
}

private class CapturingMessageRepository(
    private val captures: Captures
) : MessageRepository {
    override suspend fun listCommands(): Result<List<com.ratulsarna.ocmobile.domain.model.CommandInfo>> =
        Result.success(emptyList())

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        model: ModelSpec?,
        variant: String?,
        systemPrompt: String?,
        agent: String?
    ): Result<AssistantMessage> {
        captures.agent = agent
        captures.model = model
        return Result.success(
            AssistantMessage(
                id = "asst-1",
                sessionId = sessionId,
                createdAt = Instant.fromEpochMilliseconds(0),
                parts = emptyList()
            )
        )
    }

    override suspend fun sendMessageWithAttachments(
        sessionId: String,
        text: String?,
        attachments: List<com.ratulsarna.ocmobile.domain.model.Attachment>,
        model: ModelSpec?,
        variant: String?,
        systemPrompt: String?,
        agent: String?
    ): Result<AssistantMessage> = Result.failure(UnsupportedOperationException())

    override suspend fun sendCommand(
        sessionId: String,
        command: String,
        arguments: String,
        attachments: List<com.ratulsarna.ocmobile.domain.model.Attachment>,
        model: ModelSpec?,
        variant: String?,
        agent: String?
    ): Result<AssistantMessage> = Result.failure(UnsupportedOperationException())

    override suspend fun getMessages(sessionId: String): Result<List<com.ratulsarna.ocmobile.domain.model.Message>> =
        Result.success(emptyList())

    override suspend fun findVaultEntries(query: String): Result<List<com.ratulsarna.ocmobile.domain.model.VaultEntry>> =
        Result.success(emptyList())
}

private class SeedingAgentRepository(
    private val appSettings: MockAppSettings
) : AgentRepository {
    override suspend fun getAgents(): Result<List<Agent>> {
        appSettings.setSelectedAgent("default")
        return Result.success(
            listOf(Agent(name = "default", description = "", mode = AgentMode.PRIMARY, builtIn = true))
        )
    }

    override fun getSelectedAgent(): Flow<String?> = appSettings.getSelectedAgent()

    override suspend fun setSelectedAgent(agentName: String?) {
        appSettings.setSelectedAgent(agentName)
    }
}

private class SeedingModelRepository(
    private val appSettings: MockAppSettings
) : ModelRepository {
    override suspend fun getConnectedProviders(): Result<List<com.ratulsarna.ocmobile.domain.model.Provider>> {
        appSettings.setSelectedModel(SelectedModel(providerId = "anthropic", modelId = "claude"))
        return Result.success(emptyList())
    }

    override fun getSelectedModel(): Flow<SelectedModel?> = appSettings.getSelectedModel()

    override suspend fun setSelectedModel(model: SelectedModel?) {
        appSettings.setSelectedModel(model)
    }

    override fun getFavoriteModels(): Flow<List<SelectedModel>> = appSettings.getFavoriteModels()

    override suspend fun setFavoriteModels(models: List<SelectedModel>) {
        appSettings.setFavoriteModels(models)
    }

    override suspend fun toggleFavoriteModel(model: SelectedModel) {
        // Not needed
    }
}

private class SendDefaultsEventStream : EventStream {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override fun subscribeToEvents(): Flow<com.ratulsarna.ocmobile.domain.model.Event> = emptyFlow()
    override val isConnected: Boolean = false
    override val connectionState: StateFlow<ConnectionState> = state
    override fun disconnect() = Unit
}

private class SendDefaultsContextUsageRepository : ContextUsageRepository {
    private val flow = MutableStateFlow(ContextUsage.UNKNOWN)
    override val contextUsage: StateFlow<ContextUsage> = flow
    override suspend fun updateUsage(messages: List<com.ratulsarna.ocmobile.domain.model.Message>) = Unit
}

private class SendDefaultsPermissionRepository : PermissionRepository {
    override suspend fun getPendingRequests(): Result<List<com.ratulsarna.ocmobile.domain.model.PermissionRequest>> =
        Result.success(emptyList())
    override suspend fun reply(
        requestId: String,
        reply: com.ratulsarna.ocmobile.domain.model.PermissionReply,
        message: String?
    ) = Result.success(Unit)
}

private class SendDefaultsServerRepository : ServerRepository {
    override fun getServers(): Flow<List<ServerProfile>> = flowOf(emptyList())
    override fun getServersSnapshot(): List<ServerProfile> = emptyList()
    override fun getActiveServerId(): Flow<String?> = flowOf("srv-1")
    override fun getActiveServerIdSnapshot(): String? = "srv-1"
    override fun getActiveServerSnapshot(): ServerProfile? = null

    override suspend fun ensureInitialized(): Result<ServerProfile> = Result.success(
        ServerProfile(
            id = "srv-1",
            name = "Server",
            baseUrl = "http://example.com",
            createdAtMs = 0L
        )
    )

    override suspend fun addServer(name: String, baseUrlInput: String): Result<ServerProfile> =
        Result.failure(UnsupportedOperationException())

    override suspend fun setActiveServer(serverId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException())
}

private class SendDefaultsWorkspaceRepository(
    private val gate: CompletableDeferred<Unit>
) : WorkspaceRepository {
    private val activeWorkspace = MutableStateFlow<Workspace?>(null)

    override fun getWorkspaces(): Flow<List<Workspace>> = flowOf(emptyList())
    override fun getActiveWorkspace(): Flow<Workspace?> = activeWorkspace
    override fun getActiveWorkspaceSnapshot(): Workspace? = activeWorkspace.value

    override suspend fun ensureInitialized(): Result<Workspace> {
        gate.await()
        val workspace = Workspace(projectId = "proj-1", worktree = "/tmp/proj", name = "Proj", lastUsedAtMs = 0)
        activeWorkspace.value = workspace
        return Result.success(workspace)
    }

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)

    override suspend fun addWorkspace(directoryInput: String): Result<Workspace> =
        Result.failure(UnsupportedOperationException())

    override suspend fun activateWorkspace(projectId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException())
}
