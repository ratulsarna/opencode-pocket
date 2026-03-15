package com.ratulsarna.ocmobile.ui.screen.sidebar

import com.ratulsarna.ocmobile.data.mock.MockAppSettings
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.testing.MainDispatcherRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun SidebarViewModel_switchWorkspacePersistsSessionIdBeforeActivating() = runTest(dispatcher) {
        val activatedIds = mutableListOf<String>()
        val repo = FakeWorkspaceRepository(
            workspaces = listOf(workspace("proj-1", "/p1"), workspace("proj-2", "/p2")),
            activeWorkspace = workspace("proj-1", "/p1"),
            activateHandler = { id ->
                activatedIds.add(id)
                Result.success(Unit)
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

        vm.switchWorkspace("proj-2", "ses-target")
        advanceUntilIdle()

        assertEquals("ses-target", appSettings.getCurrentSessionIdSnapshot())
        assertEquals(listOf("proj-2"), activatedIds)
        assertEquals("proj-2", vm.uiState.value.switchedWorkspaceId)
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
        private val activateHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) },
        private val addHandler: suspend (String) -> Result<Workspace> = { error("addWorkspace not configured") }
    ) : WorkspaceRepository {
        private val _workspaces = MutableStateFlow(workspaces)
        private val _active = MutableStateFlow(activeWorkspace)

        override fun getWorkspaces(): Flow<List<Workspace>> = _workspaces
        override fun getActiveWorkspace(): Flow<Workspace?> = _active
        override fun getActiveWorkspaceSnapshot(): Workspace? = activeWorkspace
        override suspend fun ensureInitialized(): Result<Workspace> =
            activeWorkspace?.let { Result.success(it) } ?: Result.failure(RuntimeException("no active"))
        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
        override suspend fun addWorkspace(directoryInput: String): Result<Workspace> = addHandler(directoryInput)
        override suspend fun activateWorkspace(projectId: String): Result<Unit> = activateHandler(projectId)
    }

    private class FakeSessionRepository(
        private val sessions: List<Session> = emptyList(),
        private val createSessionHandler: suspend () -> Result<Session> = { error("createSession not configured") },
        private val updateCurrentSessionIdHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) }
    ) : SessionRepository {
        override suspend fun getCurrentSessionId(): Result<String> = Result.success("ses-current")
        override suspend fun getSession(sessionId: String): Result<Session> = error("not used")
        override suspend fun getSessions(search: String?, limit: Int?, start: Long?): Result<List<Session>> =
            Result.success(sessions)
        override suspend fun createSession(title: String?, parentId: String?): Result<Session> = createSessionHandler()
        override suspend fun forkSession(sessionId: String, messageId: String?): Result<Session> = error("not used")
        override suspend fun revertSession(sessionId: String, messageId: String): Result<Session> = error("not used")
        override suspend fun updateCurrentSessionId(sessionId: String): Result<Unit> = updateCurrentSessionIdHandler(sessionId)
        override suspend fun abortSession(sessionId: String): Result<Boolean> = error("not used")
    }
}
