package com.ratulsarna.ocmobile.ui.screen.chat

import com.ratulsarna.ocmobile.data.mock.MockAppSettings
import com.ratulsarna.ocmobile.domain.model.ContextUsage
import com.ratulsarna.ocmobile.domain.model.ServerProfile
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.domain.repository.AgentRepository
import com.ratulsarna.ocmobile.domain.repository.ConnectionState
import com.ratulsarna.ocmobile.domain.repository.ContextUsageRepository
import com.ratulsarna.ocmobile.domain.repository.EventStream
import com.ratulsarna.ocmobile.domain.repository.MessageRepository
import com.ratulsarna.ocmobile.domain.repository.ModelRepository
import com.ratulsarna.ocmobile.domain.repository.PermissionRepository
import com.ratulsarna.ocmobile.domain.repository.ServerRepository
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.platform.ClipboardImageReader
import com.ratulsarna.ocmobile.testing.MainDispatcherRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
class ChatViewModelBootstrapTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun ChatViewModel_doesNotFetchMessagesUntilWorkspaceBootstrapCompletes() = runTest(dispatcher) {
        val appSettings = MockAppSettings()
        appSettings.setCurrentSessionId("ses-1")

        val workspaceGate = CompletableDeferred<Unit>()
        val getMessagesCalls = mutableListOf<String>()

        val vm = ChatViewModel(
            sessionRepository = FakeSessionRepository(),
            messageRepository = FakeMessageRepository(
                onGetMessages = { sessionId -> getMessagesCalls.add(sessionId) }
            ),
            eventStream = FakeEventStream(),
            agentRepository = FakeAgentRepository(),
            modelRepository = FakeModelRepository(),
            clipboardImageReader = ClipboardImageReader(),
            contextUsageRepository = FakeContextUsageRepository(),
            appSettings = appSettings,
            permissionRepository = FakePermissionRepository(),
            serverRepository = FakeServerRepository(),
            workspaceRepository = FakeWorkspaceRepository(workspaceGate),
            clipboardPollingIntervalMs = 0L
        )

        // Let init coroutines run until they block on workspace init.
        advanceUntilIdle()
        assertEquals(emptyList(), getMessagesCalls, "Expected no message fetch before workspace bootstrap completes")

        workspaceGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            getMessagesCalls.contains("ses-1"),
            "Expected message fetch after workspace bootstrap completes"
        )
        assertTrue(
            getMessagesCalls.all { it == "ses-1" },
            "Expected only sessionId=ses-1 message fetches in this test"
        )

        assertEquals("ses-1", vm.uiState.value.currentSessionId)
    }
}

private class FakeSessionRepository : SessionRepository {
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

private class FakeMessageRepository(
    private val onGetMessages: (String) -> Unit
) : MessageRepository {
    override suspend fun listCommands(): Result<List<com.ratulsarna.ocmobile.domain.model.CommandInfo>> =
        Result.success(emptyList())

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        model: com.ratulsarna.ocmobile.domain.repository.ModelSpec?,
        variant: String?,
        systemPrompt: String?,
        agent: String?
    ): Result<com.ratulsarna.ocmobile.domain.model.AssistantMessage> =
        Result.failure(UnsupportedOperationException())

    override suspend fun sendMessageWithAttachments(
        sessionId: String,
        text: String?,
        attachments: List<com.ratulsarna.ocmobile.domain.model.Attachment>,
        model: com.ratulsarna.ocmobile.domain.repository.ModelSpec?,
        variant: String?,
        systemPrompt: String?,
        agent: String?
    ): Result<com.ratulsarna.ocmobile.domain.model.AssistantMessage> =
        Result.failure(UnsupportedOperationException())

    override suspend fun sendCommand(
        sessionId: String,
        command: String,
        arguments: String,
        attachments: List<com.ratulsarna.ocmobile.domain.model.Attachment>,
        model: com.ratulsarna.ocmobile.domain.repository.ModelSpec?,
        variant: String?,
        agent: String?
    ): Result<com.ratulsarna.ocmobile.domain.model.AssistantMessage> =
        Result.failure(UnsupportedOperationException())

    override suspend fun getMessages(sessionId: String): Result<List<com.ratulsarna.ocmobile.domain.model.Message>> {
        onGetMessages(sessionId)
        return Result.success(emptyList())
    }

    override suspend fun findVaultEntries(query: String): Result<List<com.ratulsarna.ocmobile.domain.model.VaultEntry>> =
        Result.success(emptyList())
}

private class FakeEventStream : EventStream {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override fun subscribeToEvents(): Flow<com.ratulsarna.ocmobile.domain.model.Event> = emptyFlow()
    override val isConnected: Boolean = false
    override val connectionState: StateFlow<ConnectionState> = state
    override fun disconnect() = Unit
}

private class FakeAgentRepository : AgentRepository {
    override suspend fun getAgents(): Result<List<com.ratulsarna.ocmobile.domain.model.Agent>> =
        Result.success(emptyList())
    override fun getSelectedAgent(): Flow<String?> = flowOf(null)
    override suspend fun setSelectedAgent(agentName: String?) = Unit
}

private class FakeModelRepository : ModelRepository {
    override suspend fun getConnectedProviders(): Result<List<com.ratulsarna.ocmobile.domain.model.Provider>> =
        Result.success(emptyList())
    override fun getSelectedModel(): Flow<com.ratulsarna.ocmobile.domain.model.SelectedModel?> = flowOf(null)
    override suspend fun setSelectedModel(model: com.ratulsarna.ocmobile.domain.model.SelectedModel?) = Unit
    override fun getFavoriteModels(): Flow<List<com.ratulsarna.ocmobile.domain.model.SelectedModel>> = flowOf(emptyList())
    override suspend fun setFavoriteModels(models: List<com.ratulsarna.ocmobile.domain.model.SelectedModel>) = Unit
    override suspend fun toggleFavoriteModel(model: com.ratulsarna.ocmobile.domain.model.SelectedModel) = Unit
}

private class FakeContextUsageRepository : ContextUsageRepository {
    private val flow = MutableStateFlow(ContextUsage.UNKNOWN)
    override val contextUsage: StateFlow<ContextUsage> = flow
    override suspend fun updateUsage(messages: List<com.ratulsarna.ocmobile.domain.model.Message>) = Unit
}

private class FakePermissionRepository : PermissionRepository {
    override suspend fun getPendingRequests(): Result<List<com.ratulsarna.ocmobile.domain.model.PermissionRequest>> =
        Result.success(emptyList())
    override suspend fun reply(
        requestId: String,
        reply: com.ratulsarna.ocmobile.domain.model.PermissionReply,
        message: String?
    ) = Result.success(Unit)
}

private class FakeServerRepository : ServerRepository {
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

    override suspend fun setActiveServer(serverId: String): Result<Unit> = Result.success(Unit)
}

private class FakeWorkspaceRepository(
    private val gate: CompletableDeferred<Unit>
) : WorkspaceRepository {
    private val activeWorkspace = MutableStateFlow<Workspace?>(null)

    override fun getWorkspaces(): Flow<List<Workspace>> = flowOf(emptyList())
    override fun getActiveWorkspace(): Flow<Workspace?> = activeWorkspace
    override fun getActiveWorkspaceSnapshot(): Workspace? = activeWorkspace.value

    override suspend fun ensureInitialized(): Result<Workspace> {
        gate.await()
        val workspace = Workspace(
            projectId = "proj-1",
            worktree = "/tmp/project",
            name = "Project",
            lastUsedAtMs = 0L
        )
        activeWorkspace.value = workspace
        return Result.success(workspace)
    }

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    override suspend fun addWorkspace(directoryInput: String): Result<Workspace> = Result.failure(UnsupportedOperationException())
    override suspend fun activateWorkspace(projectId: String): Result<Unit> = Result.success(Unit)
}
