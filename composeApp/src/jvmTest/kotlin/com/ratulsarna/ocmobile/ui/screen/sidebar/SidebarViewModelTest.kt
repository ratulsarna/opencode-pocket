package com.ratulsarna.ocmobile.ui.screen.sidebar

import com.ratulsarna.ocmobile.data.mock.MockAppSettings
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.testing.MainDispatcherRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class SidebarViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun SidebarViewModel_observesWorkspacesAndActiveWorkspace() = runTest(dispatcher) {
        val workspace1 = workspace("proj-1", "/path/to/project-a")
        val workspace2 = workspace("proj-2", "/path/to/project-b")
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace1, workspace2),
            activeWorkspace = workspace1
        )
        val sessionRepo = FakeSessionRepository()
        val appSettings = MockAppSettings()

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.workspaces.size)
        assertEquals("proj-1", state.activeWorkspaceId)
        assertEquals("proj-1", state.workspaces[0].workspace.projectId)
        assertEquals("proj-2", state.workspaces[1].workspace.projectId)
    }

    @Test
    fun SidebarViewModel_loadSessionsForWorkspaceFiltersAndSortsByUpdatedDesc() = runTest(dispatcher) {
        val workspace1 = workspace("proj-1", "/path/to/project-a")
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace1),
            activeWorkspace = workspace1
        )
        val sessions = listOf(
            session("ses-1", "/path/to/project-a", updatedAtMs = 100),
            session("ses-2", "/path/to/project-a", updatedAtMs = 300),
            session("ses-3", "/path/to/project-b", updatedAtMs = 200),
            session("ses-child", "/path/to/project-a", updatedAtMs = 400, parentId = "ses-1")
        )
        val sessionRepo = FakeSessionRepository(sessions = sessions)
        val appSettings = MockAppSettings()

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.loadSessionsForWorkspace("proj-1")
        advanceUntilIdle()

        val loaded = vm.uiState.value.workspaces.first().sessions
        assertEquals(listOf("ses-2", "ses-1"), loaded.map { it.id })
    }

    @Test
    fun SidebarViewModel_loadSessionsForWorkspaceUsesWorkspaceDirectory() = runTest(dispatcher) {
        val workspace1 = workspace("proj-1", "/path/to/project-a")
        val workspace2 = workspace("proj-2", "/path/to/project-b")
        val requestedDirectories = mutableListOf<String?>()
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace1, workspace2),
            activeWorkspace = workspace1
        )
        val sessionRepo = FakeSessionRepository(
            getSessionsHandler = { _, _, _, directory ->
                requestedDirectories.add(directory)
                Result.success(
                    listOf(
                        session("ses-b", "/path/to/project-b", updatedAtMs = 200),
                        session("ses-a", "/path/to/project-a", updatedAtMs = 100)
                    )
                )
            }
        )
        val appSettings = MockAppSettings()

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.loadSessionsForWorkspace("proj-2")
        advanceUntilIdle()

        val loaded = vm.uiState.value.workspaces.first { it.workspace.projectId == "proj-2" }
        assertEquals(listOf<String?>("/path/to/project-a", "/path/to/project-b"), requestedDirectories)
        assertEquals(listOf("ses-b"), loaded.sessions.map { it.id })
    }

    @Test
    fun SidebarViewModel_loadSessionsForWorkspaceClearsPreviousErrorAfterSuccess() = runTest(dispatcher) {
        val workspace1 = workspace("proj-1", "/path/to/project-a")
        var attempt = 0
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace1),
            activeWorkspace = workspace1
        )
        val sessionRepo = FakeSessionRepository(
            getSessionsHandler = { _, _, _, _ ->
                attempt += 1
                if (attempt == 1) {
                    Result.failure(IllegalStateException("load failed"))
                } else {
                    Result.success(listOf(session("ses-1", "/path/to/project-a", updatedAtMs = 100)))
                }
            }
        )

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = MockAppSettings()
        )
        advanceUntilIdle()

        assertEquals("load failed", vm.uiState.value.workspaces.first().error)

        vm.loadSessionsForWorkspace("proj-1")
        advanceUntilIdle()

        val workspace = vm.uiState.value.workspaces.first()
        assertEquals(null, workspace.error)
        assertEquals(listOf("ses-1"), workspace.sessions.map { it.id })
    }

    @Test
    fun SidebarViewModel_switchSessionCallsRepository() = runTest(dispatcher) {
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace("proj-1", "/p")),
            activeWorkspace = workspace("proj-1", "/p")
        )
        val updatedIds = mutableListOf<String>()
        val sessionRepo = FakeSessionRepository(
            updateCurrentSessionIdHandler = { id ->
                updatedIds.add(id)
                Result.success(Unit)
            }
        )
        val appSettings = MockAppSettings()

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.switchSession("ses-target")
        advanceUntilIdle()

        assertEquals(listOf("ses-target"), updatedIds)
    }

    @Test
    fun SidebarViewModel_loadsActiveSessionTitleFromRepository() = runTest(dispatcher) {
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace("proj-1", "/p1")),
            activeWorkspace = workspace("proj-1", "/p1")
        )
        val appSettings = MockAppSettings()
        appSettings.setCurrentSessionId("ses-target")
        val sessionRepo = FakeSessionRepository(
            getSessionHandler = { sessionId ->
                Result.success(session(sessionId, "/p1", updatedAtMs = 1).copy(title = "My session title"))
            }
        )

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        assertEquals("My session title", vm.uiState.value.activeSessionTitle)
    }

    @Test
    fun SidebarViewModel_switchWorkspaceActivatesBeforePersistingSessionId() = runTest(dispatcher) {
        val operations = mutableListOf<String>()
        val appSettings = MockAppSettings()
        appSettings.setActiveServerId("server-1")
        appSettings.setInstallationIdForServer("server-1", "inst-1")

        val workspace1 = workspace("proj-1", "/p1")
        val workspace2 = workspace("proj-2", "/p2")
        appSettings.setWorkspacesForInstallation("inst-1", listOf(workspace1, workspace2))
        appSettings.setActiveWorkspace("inst-1", workspace1)

        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace1, workspace2),
            activeWorkspace = workspace1,
            appSettings = appSettings,
            activateHandler = { id ->
                operations.add("activate:$id")
                Result.success(Unit)
            }
        )
        val sessionRepo = FakeSessionRepository(
            updateCurrentSessionIdHandler = { sessionId ->
                operations.add("persist:$sessionId")
                appSettings.setCurrentSessionId(sessionId)
                Result.success(Unit)
            }
        )

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.switchWorkspace("proj-2", "ses-target")
        advanceUntilIdle()

        assertEquals("proj-2", appSettings.getActiveWorkspaceSnapshot()?.projectId)
        assertEquals("ses-target", appSettings.getCurrentSessionIdSnapshot())
        assertEquals(listOf("activate:proj-2", "persist:ses-target"), operations)
        assertEquals("proj-2", vm.uiState.value.switchedWorkspaceId)
    }

    @Test
    fun SidebarViewModel_switchWorkspaceExposesSessionPersistenceFailure() = runTest(dispatcher) {
        val workspace1 = workspace("proj-1", "/p1")
        val workspace2 = workspace("proj-2", "/p2")
        val appSettings = MockAppSettings()
        appSettings.setActiveServerId("server-1")
        appSettings.setInstallationIdForServer("server-1", "inst-1")
        appSettings.setWorkspacesForInstallation("inst-1", listOf(workspace1, workspace2))
        appSettings.setActiveWorkspace("inst-1", workspace1)

        val vm = SidebarViewModel(
            workspaceRepository = FakeWorkspaceRepository(
                workspaces = listOf(workspace1, workspace2),
                activeWorkspace = workspace1,
                appSettings = appSettings
            ),
            sessionRepository = FakeSessionRepository(
                updateCurrentSessionIdHandler = {
                    Result.failure(IllegalStateException("persist failed"))
                }
            ),
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.switchWorkspace("proj-2", "ses-target")
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.isSwitchingWorkspace)
        assertEquals(null, vm.uiState.value.switchedWorkspaceId)
        assertEquals("persist failed", vm.uiState.value.operationErrorMessage)
    }

    @Test
    fun SidebarViewModel_createSessionFailureInInactiveWorkspaceStillSignalsWorkspaceSwitch() = runTest(dispatcher) {
        val workspace1 = workspace("proj-1", "/p1")
        val workspace2 = workspace("proj-2", "/p2")
        val appSettings = MockAppSettings()
        appSettings.setActiveServerId("server-1")
        appSettings.setInstallationIdForServer("server-1", "inst-1")
        appSettings.setWorkspacesForInstallation("inst-1", listOf(workspace1, workspace2))
        appSettings.setActiveWorkspace("inst-1", workspace1)

        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace1, workspace2),
            activeWorkspace = workspace1,
            appSettings = appSettings
        )
        val sessionRepo = FakeSessionRepository(
            createSessionHandler = { Result.failure(IllegalStateException("create failed")) }
        )

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.createSession("proj-2")
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.isCreatingSession)
        assertEquals(null, vm.uiState.value.switchedWorkspaceId)
        assertEquals("create failed", vm.uiState.value.operationErrorMessage)
        assertEquals("proj-2", appSettings.getActiveWorkspaceSnapshot()?.projectId)
    }

    @Test
    fun SidebarViewModel_clearOperationErrorRemovesMessage() = runTest(dispatcher) {
        val workspace1 = workspace("proj-1", "/p1")
        val vm = SidebarViewModel(
            workspaceRepository = FakeWorkspaceRepository(
                workspaces = listOf(workspace1),
                activeWorkspace = workspace1
            ),
            sessionRepository = FakeSessionRepository(
                createSessionHandler = { Result.failure(IllegalStateException("create failed")) }
            ),
            appSettings = MockAppSettings()
        )
        advanceUntilIdle()

        vm.createSession("proj-1")
        advanceUntilIdle()
        assertEquals("create failed", vm.uiState.value.operationErrorMessage)

        vm.clearOperationError()

        assertEquals(null, vm.uiState.value.operationErrorMessage)
    }

    @Test
    fun SidebarViewModel_createSessionInActiveWorkspace() = runTest(dispatcher) {
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace("proj-1", "/p1")),
            activeWorkspace = workspace("proj-1", "/p1")
        )
        val sessionRepo = FakeSessionRepository(
            createSessionHandler = { Result.success(session("ses-new", "/p1", updatedAtMs = 1)) }
        )
        val appSettings = MockAppSettings()

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.createSession("proj-1")
        advanceUntilIdle()

        assertEquals("ses-new", vm.uiState.value.createdSessionId)
        assertEquals(null, vm.uiState.value.switchedWorkspaceId)
    }

    @Test
    fun SidebarViewModel_addWorkspaceCallsRepository() = runTest(dispatcher) {
        val addedDirs = mutableListOf<String>()
        val repo = FakeWorkspaceRepository(
            workspaces = emptyList(),
            activeWorkspace = null,
            addHandler = { dir ->
                addedDirs.add(dir)
                Result.success(workspace("proj-new", dir))
            }
        )
        val sessionRepo = FakeSessionRepository()
        val appSettings = MockAppSettings()

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.addWorkspace("/new/path")
        advanceUntilIdle()

        assertEquals(listOf("/new/path"), addedDirs)
        assertEquals(false, vm.uiState.value.isCreatingWorkspace)
        assertEquals(null, vm.uiState.value.workspaceCreationError)
    }

    @Test
    fun SidebarViewModel_addWorkspaceExposesFailure() = runTest(dispatcher) {
        val repo = FakeWorkspaceRepository(
            workspaces = emptyList(),
            activeWorkspace = null,
            addHandler = { Result.failure(IllegalStateException("Workspace already exists")) }
        )
        val sessionRepo = FakeSessionRepository()
        val appSettings = MockAppSettings()

        val vm = SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = sessionRepo,
            appSettings = appSettings
        )
        advanceUntilIdle()

        vm.addWorkspace("/existing/path")
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.isCreatingWorkspace)
        assertEquals("Workspace already exists", vm.uiState.value.workspaceCreationError)
    }

    @Test
    fun SidebarViewModel_refreshesWorkspacesAfterInitialization() = runTest(dispatcher) {
        var refreshCount = 0
        val workspace1 = workspace("proj-1", "/p1")
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace1),
            activeWorkspace = workspace1,
            refreshHandler = {
                refreshCount += 1
                Result.success(Unit)
            }
        )

        SidebarViewModel(
            workspaceRepository = repo,
            sessionRepository = FakeSessionRepository(),
            appSettings = MockAppSettings()
        )
        advanceUntilIdle()

        assertEquals(1, refreshCount)
    }

    private fun workspace(projectId: String, worktree: String, name: String? = null): Workspace =
        Workspace(projectId = projectId, worktree = worktree, name = name)

    private fun session(
        id: String,
        directory: String,
        updatedAtMs: Long,
        parentId: String? = null
    ): Session {
        val instant = Instant.fromEpochMilliseconds(updatedAtMs)
        return Session(id = id, directory = directory, title = id, createdAt = instant, updatedAt = instant, parentId = parentId)
    }

    private class FakeWorkspaceRepository(
        private val workspaces: List<Workspace> = emptyList(),
        private val activeWorkspace: Workspace? = null,
        private val appSettings: MockAppSettings? = null,
        private val refreshHandler: suspend () -> Result<Unit> = { Result.success(Unit) },
        private val activateHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) },
        private val addHandler: suspend (String) -> Result<Workspace> = { error("addWorkspace not configured") }
    ) : WorkspaceRepository {
        private val _workspaces = MutableStateFlow(workspaces)
        private val _active = MutableStateFlow(activeWorkspace)

        override fun getWorkspaces(): Flow<List<Workspace>> = _workspaces
        override fun getActiveWorkspace(): Flow<Workspace?> = _active
        override fun getActiveWorkspaceSnapshot(): Workspace? = _active.value
        override suspend fun ensureInitialized(): Result<Workspace> =
            _active.value?.let { Result.success(it) } ?: Result.failure(RuntimeException("no active"))
        override suspend fun refresh(): Result<Unit> = refreshHandler()
        override suspend fun addWorkspace(directoryInput: String): Result<Workspace> = addHandler(directoryInput)
        override suspend fun activateWorkspace(projectId: String): Result<Unit> {
            val result = activateHandler(projectId)
            if (result.isSuccess) {
                val workspace = _workspaces.value.firstOrNull { it.projectId == projectId }
                    ?: return Result.failure(IllegalArgumentException("Workspace not found: $projectId"))
                _active.value = workspace
                appSettings?.setActiveWorkspace("inst-1", workspace)
            }
            return result
        }
    }

    private class FakeSessionRepository(
        private val sessions: List<Session> = emptyList(),
        private val getSessionHandler: suspend (String) -> Result<Session> = { sessionId ->
            val instant = Instant.fromEpochMilliseconds(0)
            Result.success(
                Session(
                    id = sessionId,
                    directory = "/unused",
                    title = sessionId,
                    createdAt = instant,
                    updatedAt = instant,
                    parentId = null
                )
            )
        },
        private val getSessionsHandler: suspend (String?, Int?, Long?, String?) -> Result<List<Session>> = { _, _, _, _ ->
            Result.success(sessions)
        },
        private val createSessionHandler: suspend () -> Result<Session> = { error("createSession not configured") },
        private val updateCurrentSessionIdHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) }
    ) : SessionRepository {
        override suspend fun getCurrentSessionId(): Result<String> = Result.success("ses-current")
        override suspend fun getSession(sessionId: String): Result<Session> = getSessionHandler(sessionId)
        override suspend fun getSessions(search: String?, limit: Int?, start: Long?, directory: String?): Result<List<Session>> =
            getSessionsHandler(search, limit, start, directory)
        override suspend fun createSession(title: String?, parentId: String?): Result<Session> = createSessionHandler()
        override suspend fun forkSession(sessionId: String, messageId: String?): Result<Session> = error("not used")
        override suspend fun revertSession(sessionId: String, messageId: String): Result<Session> = error("not used")
        override suspend fun updateCurrentSessionId(sessionId: String): Result<Unit> = updateCurrentSessionIdHandler(sessionId)
        override suspend fun abortSession(sessionId: String): Result<Boolean> = error("not used")
    }
}
