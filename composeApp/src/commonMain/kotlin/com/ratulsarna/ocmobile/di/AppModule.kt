package com.ratulsarna.ocmobile.di

import com.ratulsarna.ocmobile.data.api.ApiClients
import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.api.createApiClients
import com.ratulsarna.ocmobile.data.mock.MockAppSettings
import com.ratulsarna.ocmobile.data.mock.MockEventStream
import com.ratulsarna.ocmobile.data.mock.MockOpenCodeApi
import com.ratulsarna.ocmobile.data.mock.MockResponseGenerator
import com.ratulsarna.ocmobile.data.mock.MockState
import com.ratulsarna.ocmobile.data.repository.AgentRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.ContextUsageRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.EventStreamImpl
import com.ratulsarna.ocmobile.data.repository.MessageRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.ModelRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.PermissionRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.ProjectFileRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.WorkspaceRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.SessionRepositoryImpl
import com.ratulsarna.ocmobile.data.repository.VaultFileRepositoryImpl
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.data.settings.AppSettingsImpl
import com.ratulsarna.ocmobile.domain.repository.AgentRepository
import com.ratulsarna.ocmobile.domain.repository.ContextUsageRepository
import com.ratulsarna.ocmobile.domain.repository.EventStream
import com.ratulsarna.ocmobile.domain.repository.MessageRepository
import com.ratulsarna.ocmobile.domain.repository.ModelRepository
import com.ratulsarna.ocmobile.domain.repository.PermissionRepository
import com.ratulsarna.ocmobile.domain.repository.ProjectFileRepository
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.domain.repository.VaultFileRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.platform.ClipboardImageReader
import com.ratulsarna.ocmobile.ui.screen.chat.ChatViewModel
import com.ratulsarna.ocmobile.ui.screen.docs.MarkdownFileViewerViewModel
import com.ratulsarna.ocmobile.ui.screen.filebrowser.FileBrowserViewModel
import com.ratulsarna.ocmobile.ui.screen.connect.ConnectViewModel
import com.ratulsarna.ocmobile.ui.screen.sessions.SessionsViewModel
import com.ratulsarna.ocmobile.ui.screen.settings.SettingsViewModel
import com.ratulsarna.ocmobile.ui.screen.workspaces.WorkspacesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Simple factory for creating dependencies.
 *
 * This repo is an OpenCode-only mobile client. Non-OpenCode services (notifications, tasks,
 * session-id infra, push) are intentionally not wired here.
 */
object AppModule {
    /**
     * Set to true to enable mock mode (no real backend needed).
     * MUST be set before accessing any dependencies.
     */
    var isMockMode: Boolean = false

    // Shared mock state (only initialized in mock mode)
    private val mockState: MockState by lazy { MockState() }

    private data class RealGraph(
        val baseUrl: String,
        val apiClients: ApiClients,
        val scope: CoroutineScope,
        val sessionRepository: SessionRepository,
        val messageRepository: MessageRepository,
        val permissionRepository: PermissionRepository,
        val vaultFileRepository: VaultFileRepository,
        val projectFileRepository: ProjectFileRepository,
        val workspaceRepository: WorkspaceRepository,
        val agentRepository: AgentRepository,
        val modelRepository: ModelRepository,
        val eventStream: EventStream,
        val contextUsageRepository: ContextUsageRepository
    )

    private data class MockGraph(
        val scope: CoroutineScope,
        val sessionRepository: SessionRepository,
        val messageRepository: MessageRepository,
        val permissionRepository: PermissionRepository,
        val vaultFileRepository: VaultFileRepository,
        val projectFileRepository: ProjectFileRepository,
        val workspaceRepository: WorkspaceRepository,
        val agentRepository: AgentRepository,
        val modelRepository: ModelRepository,
        val eventStream: EventStream,
        val contextUsageRepository: ContextUsageRepository
    )

    private var realGraph: RealGraph? = null
    private var mockGraph: MockGraph? = null

    // App Settings - persistent storage for agent selection and theme preference
    internal val appSettings: AppSettings by lazy {
        if (isMockMode) {
            MockAppSettings()
        } else {
            AppSettingsImpl()
        }
    }

    private val serverRepository: com.ratulsarna.ocmobile.domain.repository.ServerRepository by lazy {
        com.ratulsarna.ocmobile.data.repository.ServerRepositoryImpl(
            appSettings = appSettings,
            defaultBaseUrl = { com.ratulsarna.ocmobile.data.api.ApiConfig.OPENCODE_API_BASE_URL }
        )
    }

    private val clipboardImageReader: ClipboardImageReader by lazy {
        ClipboardImageReader()
    }

    private fun ensureMockGraph(): MockGraph {
        val existing = mockGraph
        if (existing != null) return existing

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val responseGenerator = MockResponseGenerator(mockState, scope)
        val openCodeApi: OpenCodeApi = MockOpenCodeApi(mockState, responseGenerator)

        val sessionRepository: SessionRepository = SessionRepositoryImpl(openCodeApi, appSettings)
        val messageRepository: MessageRepository = MessageRepositoryImpl(openCodeApi)
        val permissionRepository: PermissionRepository = PermissionRepositoryImpl(openCodeApi)
        val vaultFileRepository: VaultFileRepository = VaultFileRepositoryImpl(openCodeApi, appSettings)
        val projectFileRepository: ProjectFileRepository = ProjectFileRepositoryImpl(openCodeApi)
        val workspaceRepository: WorkspaceRepository = WorkspaceRepositoryImpl(openCodeApi, appSettings)
        val agentRepository: AgentRepository = AgentRepositoryImpl(openCodeApi, appSettings)
        val modelRepository: ModelRepository = ModelRepositoryImpl(openCodeApi, appSettings)
        val eventStream: EventStream = MockEventStream(mockState)
        val contextUsageRepository: ContextUsageRepository = ContextUsageRepositoryImpl(modelRepository)

        return MockGraph(
            scope = scope,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            permissionRepository = permissionRepository,
            vaultFileRepository = vaultFileRepository,
            projectFileRepository = projectFileRepository,
            workspaceRepository = workspaceRepository,
            agentRepository = agentRepository,
            modelRepository = modelRepository,
            eventStream = eventStream,
            contextUsageRepository = contextUsageRepository
        ).also { mockGraph = it }
    }

    private fun ensureRealGraph(): RealGraph {
        val servers = serverRepository.getServersSnapshot()
        val activeId = serverRepository.getActiveServerIdSnapshot()
        val baseUrl = (
            servers.firstOrNull { it.id == activeId }?.baseUrl
                ?: servers.firstOrNull()?.baseUrl
                ?: com.ratulsarna.ocmobile.data.api.ApiConfig.OPENCODE_API_BASE_URL
            ).trimEnd('/')

        val existing = realGraph
        if (existing != null && existing.baseUrl == baseUrl) return existing

        // Tear down any existing graph before rebuilding.
        existing?.let { old ->
            old.scope.cancel()
            old.apiClients.close()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val apiClients = createApiClients(
            baseUrl = baseUrl,
            directoryProvider = { appSettings.getActiveWorkspaceSnapshot()?.worktree },
            authTokenProvider = { appSettings.getAuthTokenForActiveServerSnapshot() }
        )
        val openCodeApi = apiClients.openCodeApi

        val sessionRepository: SessionRepository = SessionRepositoryImpl(openCodeApi, appSettings)
        val messageRepository: MessageRepository = MessageRepositoryImpl(openCodeApi)
        val permissionRepository: PermissionRepository = PermissionRepositoryImpl(openCodeApi)
        val vaultFileRepository: VaultFileRepository = VaultFileRepositoryImpl(openCodeApi, appSettings)
        val projectFileRepository: ProjectFileRepository = ProjectFileRepositoryImpl(openCodeApi)
        val workspaceRepository: WorkspaceRepository = WorkspaceRepositoryImpl(openCodeApi, appSettings)
        val agentRepository: AgentRepository = AgentRepositoryImpl(openCodeApi, appSettings)
        val modelRepository: ModelRepository = ModelRepositoryImpl(openCodeApi, appSettings)
        val reconnectKey = combine(
            appSettings.getActiveWorkspace().map { it?.worktree },
            appSettings.getAuthTokenForActiveServer()
        ) { worktree, token ->
            "${worktree.orEmpty()}|${token.orEmpty()}"
        }

        val eventStream: EventStream = EventStreamImpl(
            sseClient = apiClients.sseClient,
            scope = scope,
            reconnectKey = reconnectKey
        )
        val contextUsageRepository: ContextUsageRepository = ContextUsageRepositoryImpl(modelRepository)

        return RealGraph(
            baseUrl = baseUrl,
            apiClients = apiClients,
            scope = scope,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            permissionRepository = permissionRepository,
            vaultFileRepository = vaultFileRepository,
            projectFileRepository = projectFileRepository,
            workspaceRepository = workspaceRepository,
            agentRepository = agentRepository,
            modelRepository = modelRepository,
            eventStream = eventStream,
            contextUsageRepository = contextUsageRepository
        ).also { realGraph = it }
    }

    private fun graphSessionRepository(): SessionRepository =
        if (isMockMode) ensureMockGraph().sessionRepository else ensureRealGraph().sessionRepository

    private fun graphMessageRepository(): MessageRepository =
        if (isMockMode) ensureMockGraph().messageRepository else ensureRealGraph().messageRepository

    private fun graphPermissionRepository(): PermissionRepository =
        if (isMockMode) ensureMockGraph().permissionRepository else ensureRealGraph().permissionRepository

    private fun graphAgentRepository(): AgentRepository =
        if (isMockMode) ensureMockGraph().agentRepository else ensureRealGraph().agentRepository

    private fun graphModelRepository(): ModelRepository =
        if (isMockMode) ensureMockGraph().modelRepository else ensureRealGraph().modelRepository

    private fun graphEventStream(): EventStream =
        if (isMockMode) ensureMockGraph().eventStream else ensureRealGraph().eventStream

    private fun graphVaultRepository(): VaultFileRepository =
        if (isMockMode) ensureMockGraph().vaultFileRepository else ensureRealGraph().vaultFileRepository

    private fun graphProjectFileRepository(): ProjectFileRepository =
        if (isMockMode) ensureMockGraph().projectFileRepository else ensureRealGraph().projectFileRepository

    private fun graphWorkspaceRepository(): WorkspaceRepository =
        if (isMockMode) ensureMockGraph().workspaceRepository else ensureRealGraph().workspaceRepository

    private fun graphContextUsageRepository(): ContextUsageRepository =
        if (isMockMode) ensureMockGraph().contextUsageRepository else ensureRealGraph().contextUsageRepository

    // ViewModels
    fun createChatViewModel(): ChatViewModel {
        return ChatViewModel(
            sessionRepository = graphSessionRepository(),
            messageRepository = graphMessageRepository(),
            eventStream = graphEventStream(),
            agentRepository = graphAgentRepository(),
            modelRepository = graphModelRepository(),
            clipboardImageReader = clipboardImageReader,
            contextUsageRepository = graphContextUsageRepository(),
            appSettings = appSettings,
            permissionRepository = graphPermissionRepository(),
            serverRepository = serverRepository,
            workspaceRepository = graphWorkspaceRepository()
        )
    }

    fun createSessionsViewModel(): SessionsViewModel {
        return SessionsViewModel(
            sessionRepository = graphSessionRepository(),
            appSettings = appSettings
        )
    }

    fun createConnectViewModel(): ConnectViewModel {
        return ConnectViewModel(
            serverRepository = serverRepository,
            appSettings = appSettings
        )
    }

    fun createSettingsViewModel(): SettingsViewModel {
        return SettingsViewModel(
            eventStream = graphEventStream(),
            agentRepository = graphAgentRepository(),
            modelRepository = graphModelRepository(),
            contextUsageRepository = graphContextUsageRepository(),
            serverRepository = serverRepository,
            appSettings = appSettings,
            workspaceRepository = graphWorkspaceRepository()
        )
    }

    fun createWorkspacesViewModel(): WorkspacesViewModel {
        return WorkspacesViewModel(workspaceRepository = graphWorkspaceRepository())
    }

    fun createMarkdownFileViewerViewModel(path: String): MarkdownFileViewerViewModel {
        return MarkdownFileViewerViewModel(path, graphVaultRepository())
    }

    fun createFileBrowserViewModel(): FileBrowserViewModel {
        return FileBrowserViewModel(graphProjectFileRepository(), graphEventStream(), graphVaultRepository())
    }
}
