# Sidebar Navigation Rework — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace separate Workspace/Sessions screens with a unified sidebar using `NavigationSplitView`, featuring workspaces with nested sessions and Liquid Glass styling.

**Architecture:** `NavigationSplitView` at the root (when paired) with a custom sidebar containing workspace cards with expandable session lists. A new `SidebarViewModel` (Kotlin) absorbs logic from the deleted `SessionsViewModel` and `WorkspacesViewModel`. The detail column retains the existing chat + settings navigation stack.

**Tech Stack:** Kotlin Multiplatform (shared ViewModel), SwiftUI (`NavigationSplitView`, Liquid Glass), SKIE (Kotlin-Swift bridging), UIKit (chat message list — unchanged)

---

## File Structure

### New Files

| File | Responsibility |
|------|----------------|
| `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/SidebarViewModel.kt` | Combined workspace + session state, fetching, switching, creation |
| `composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/SidebarViewModelTest.kt` | Unit tests for SidebarViewModel |
| `iosApp/iosApp/SwiftUIInterop/WorkspacesSidebarView.swift` | Sidebar root view: toolbar, ScrollView + LazyVStack of workspace cards |
| `iosApp/iosApp/SwiftUIInterop/WorkspaceCardView.swift` | Single workspace card: glass surface, expand/collapse, session rows, + button |

### Modified Files

| File | Changes |
|------|---------|
| `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/di/AppModule.kt` | Add `createSidebarViewModel()` factory, keep old factories until deletion step |
| `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/IosViewModelOwners.kt` | Add `sidebarViewModel()` to `IosAppViewModelOwner`, remove `workspacesViewModel()` and `sessionsViewModel()` |
| `iosApp/iosApp/SwiftUIInterop/KmpOwners.swift` | No changes needed (accesses `IosAppViewModelOwner` which is KMP-bridged) |
| `iosApp/iosApp/SwiftUIInterop/SwiftUIAppRootView.swift` | Replace `NavigationStack` with `NavigationSplitView`, remove sessions sheet, wire sidebar, remove `.workspaces` route |
| `iosApp/iosApp/ChatUIKit/ChatScreenChromeView.swift` | Replace sessions button with hamburger, update title/subtitle to session name + workspace path |
| `iosApp/iosApp/ChatUIKit/SwiftUIChatUIKitView.swift` | Replace `onOpenSessions` with `onToggleSidebar`, pass new toolbar data |
| `iosApp/iosApp/SwiftUIInterop/SwiftUISettingsViews.swift` | Remove Workspace row, Sessions section, and their callback parameters |

### Deleted Files

| File | Reason |
|------|--------|
| `iosApp/iosApp/SwiftUIInterop/SwiftUISessionsViews.swift` | Replaced by sidebar |
| `iosApp/iosApp/SwiftUIInterop/SwiftUIWorkspacesViews.swift` | Replaced by sidebar |
| `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModel.kt` | Logic absorbed into SidebarViewModel |
| `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/workspaces/WorkspacesViewModel.kt` | Logic absorbed into SidebarViewModel |
| `composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModelTest.kt` | Replaced by SidebarViewModelTest |

---

## Chunk 1: SidebarViewModel (Kotlin)

### Task 1: Create SidebarViewModel with UiState

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/SidebarViewModel.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/SidebarViewModelTest.kt`

- [ ] **Step 1: Write the data classes and empty ViewModel**

Create the file with the UiState data classes and an empty ViewModel shell:

```kotlin
package com.ratulsarna.ocmobile.ui.screen.sidebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.util.OcMobileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SidebarVM"
private const val DEFAULT_RECENT_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L

class SidebarViewModel(
    private val workspaceRepository: WorkspaceRepository,
    private val sessionRepository: SessionRepository,
    private val appSettings: AppSettings
) : ViewModel() {

    private val _uiState = MutableStateFlow(SidebarUiState())
    val uiState: StateFlow<SidebarUiState> = _uiState.asStateFlow()

    init {
        ensureInitialized()
        observeWorkspaces()
        observeActiveSessionId()
    }

    private fun ensureInitialized() {
        viewModelScope.launch {
            workspaceRepository.ensureInitialized()
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to initialize workspaces: ${error.message}")
                }
        }
    }

    private fun observeWorkspaces() {
        viewModelScope.launch {
            combine(
                workspaceRepository.getWorkspaces(),
                workspaceRepository.getActiveWorkspace()
            ) { workspaces, active ->
                workspaces to active
            }.collect { (workspaces, active) ->
                _uiState.update {
                    it.copy(
                        activeWorkspaceId = active?.projectId,
                        workspaces = workspaces.map { workspace ->
                            val existing = it.workspaces.find { w -> w.workspace.projectId == workspace.projectId }
                            existing?.copy(workspace = workspace) ?: WorkspaceWithSessions(workspace = workspace)
                        }
                    )
                }
            }
        }
    }

    private fun observeActiveSessionId() {
        viewModelScope.launch {
            appSettings.getCurrentSessionId().collect { id ->
                _uiState.update { it.copy(activeSessionId = id) }
            }
        }
    }

    /**
     * Fetch sessions for a specific workspace. Called when a workspace is first expanded.
     * Sessions are fetched globally and filtered client-side by matching
     * Session.directory to Workspace.worktree.
     */
    fun loadSessionsForWorkspace(projectId: String) {
        val workspace = _uiState.value.workspaces.find { it.workspace.projectId == projectId } ?: return
        if (workspace.isLoading) return

        _uiState.update { state ->
            state.copy(workspaces = state.workspaces.map {
                if (it.workspace.projectId == projectId) it.copy(isLoading = true, error = null) else it
            })
        }

        viewModelScope.launch {
            val start = kotlin.time.Clock.System.now().toEpochMilliseconds() - DEFAULT_RECENT_WINDOW_MS
            sessionRepository.getSessions(search = null, limit = null, start = start)
                .onSuccess { allSessions ->
                    val filtered = allSessions
                        .filter { it.parentId == null && it.directory == workspace.workspace.worktree }
                        .sortedByDescending { it.updatedAt }
                    _uiState.update { state ->
                        state.copy(workspaces = state.workspaces.map {
                            if (it.workspace.projectId == projectId) {
                                it.copy(sessions = filtered, isLoading = false)
                            } else it
                        })
                    }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to load sessions for $projectId: ${error.message}")
                    _uiState.update { state ->
                        state.copy(workspaces = state.workspaces.map {
                            if (it.workspace.projectId == projectId) {
                                it.copy(isLoading = false, error = error.message ?: "Failed to load sessions")
                            } else it
                        })
                    }
                }
        }
    }

    /**
     * Switch to a session in the same workspace.
     */
    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.updateCurrentSessionId(sessionId)
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to switch session: ${error.message}")
                }
        }
    }

    /**
     * Switch to a different workspace and activate a specific session.
     * Writes the target session ID to AppSettings before triggering the workspace switch,
     * so it survives the app reset.
     */
    fun switchWorkspace(projectId: String, sessionId: String?) {
        if (_uiState.value.isSwitchingWorkspace) return

        _uiState.update { it.copy(isSwitchingWorkspace = true) }

        viewModelScope.launch {
            // Persist the target session ID so it survives the app reset
            if (sessionId != null) {
                appSettings.setCurrentSessionId(sessionId)
            }
            workspaceRepository.activateWorkspace(projectId)
                .onSuccess {
                    _uiState.update { it.copy(isSwitchingWorkspace = false, switchedWorkspaceId = projectId) }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to switch workspace: ${error.message}")
                    _uiState.update { it.copy(isSwitchingWorkspace = false) }
                }
        }
    }

    fun clearWorkspaceSwitch() {
        _uiState.update { it.copy(switchedWorkspaceId = null) }
    }

    /**
     * Create a new session in a workspace.
     * If the workspace differs from active, triggers a workspace switch first.
     */
    fun createSession(workspaceProjectId: String) {
        if (_uiState.value.isCreatingSession) return
        _uiState.update { it.copy(isCreatingSession = true) }

        viewModelScope.launch {
            val isActiveWorkspace = workspaceProjectId == _uiState.value.activeWorkspaceId

            if (!isActiveWorkspace) {
                // Switch workspace first, then create session
                workspaceRepository.activateWorkspace(workspaceProjectId)
                    .onFailure { error ->
                        OcMobileLog.w(TAG, "Failed to switch workspace for new session: ${error.message}")
                        _uiState.update { it.copy(isCreatingSession = false) }
                        return@launch
                    }
            }

            sessionRepository.createSession(parentId = null)
                .onSuccess { session ->
                    _uiState.update { it.copy(
                        isCreatingSession = false,
                        createdSessionId = session.id,
                        // If workspace changed, signal for app reset
                        switchedWorkspaceId = if (!isActiveWorkspace) workspaceProjectId else null
                    ) }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to create session: ${error.message}")
                    _uiState.update { it.copy(isCreatingSession = false) }
                }
        }
    }

    fun clearCreatedSession() {
        _uiState.update { it.copy(createdSessionId = null) }
    }

    fun addWorkspace(directoryInput: String) {
        if (_uiState.value.isCreatingWorkspace) return
        _uiState.update { it.copy(isCreatingWorkspace = true) }

        viewModelScope.launch {
            workspaceRepository.addWorkspace(directoryInput)
                .onSuccess {
                    _uiState.update { it.copy(isCreatingWorkspace = false) }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to add workspace: ${error.message}")
                    _uiState.update { it.copy(isCreatingWorkspace = false) }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            workspaceRepository.refresh()
        }
    }
}

data class SidebarUiState(
    val workspaces: List<WorkspaceWithSessions> = emptyList(),
    val activeWorkspaceId: String? = null,
    val activeSessionId: String? = null,
    val isCreatingSession: Boolean = false,
    val isCreatingWorkspace: Boolean = false,
    val isSwitchingWorkspace: Boolean = false,
    val switchedWorkspaceId: String? = null,
    val createdSessionId: String? = null
)

data class WorkspaceWithSessions(
    val workspace: Workspace,
    val sessions: List<Session> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
```

- [ ] **Step 2: Verify the file compiles**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && ./gradlew composeApp:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Write failing test — observes workspaces and groups sessions**

Create `composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/SidebarViewModelTest.kt`:

```kotlin
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
            session("ses-3", "/path/to/project-b", updatedAtMs = 200), // different workspace
            session("ses-child", "/path/to/project-a", updatedAtMs = 400, parentId = "ses-1") // child
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
        assertEquals(null, vm.uiState.value.switchedWorkspaceId) // no workspace switch
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

    // --- Helpers ---

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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && ./gradlew composeApp:jvmTest --tests "com.ratulsarna.ocmobile.ui.screen.sidebar.SidebarViewModelTest"`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/SidebarViewModel.kt \
       composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/SidebarViewModelTest.kt
git commit -m "feat: add SidebarViewModel with workspace + session management"
```

---

### Task 2: Wire SidebarViewModel into DI and iOS bridge

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/di/AppModule.kt:263-291`
- Modify: `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/IosViewModelOwners.kt:33-76`

- [ ] **Step 1: Add `createSidebarViewModel()` factory to AppModule**

In `AppModule.kt`, add after line 261 (after `createChatViewModel`):

```kotlin
fun createSidebarViewModel(): SidebarViewModel {
    return SidebarViewModel(
        workspaceRepository = graphWorkspaceRepository(),
        sessionRepository = graphSessionRepository(),
        appSettings = appSettings
    )
}
```

Add the import at the top of the file:

```kotlin
import com.ratulsarna.ocmobile.ui.screen.sidebar.SidebarViewModel
```

- [ ] **Step 2: Add `sidebarViewModel()` to `IosAppViewModelOwner`**

In `IosViewModelOwners.kt`, add after the `workspacesViewModel()` method (line 48):

```kotlin
/** App-scoped sidebar combining workspaces + sessions. */
fun sidebarViewModel(): SidebarViewModel = get(key = "sidebar") { AppModule.createSidebarViewModel() }
```

Add the import:

```kotlin
import com.ratulsarna.ocmobile.ui.screen.sidebar.SidebarViewModel
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && ./gradlew composeApp:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/di/AppModule.kt \
       composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/IosViewModelOwners.kt
git commit -m "feat: wire SidebarViewModel into DI and iOS bridge"
```

---

## Chunk 2: Sidebar UI (Swift)

### Task 3: Create WorkspaceCardView

**Files:**
- Create: `iosApp/iosApp/SwiftUIInterop/WorkspaceCardView.swift`

- [ ] **Step 1: Create the workspace card component**

This is a self-contained SwiftUI view for a single workspace. It handles:
- Workspace header row with folder icon, name, and `+` (new session) button
- Tap on header toggles expand/collapse
- When expanded: shows session rows (up to 3 or all if fully expanded)
- Active session indicator (accent dot)
- "View N more sessions" CTA when there are more than 3

```swift
import SwiftUI
import ComposeApp

@MainActor
struct WorkspaceCardView: View {
    let workspaceWithSessions: WorkspaceWithSessions
    let isActive: Bool
    let activeSessionId: String?
    let isExpanded: Bool
    let isFullyExpanded: Bool
    let isCreatingSession: Bool
    let onToggleExpand: () -> Void
    let onToggleFullExpand: () -> Void
    let onSelectSession: (String) -> Void
    let onCreateSession: () -> Void

    private var displayTitle: String {
        if let name = workspaceWithSessions.workspace.name, !name.isEmpty {
            return name
        }
        let worktree = workspaceWithSessions.workspace.worktree
        return (worktree as NSString).lastPathComponent.isEmpty
            ? workspaceWithSessions.workspace.projectId
            : (worktree as NSString).lastPathComponent
    }

    private var sessions: [Session] {
        let all = workspaceWithSessions.sessions
        if isFullyExpanded { return Array(all) }
        return Array(all.prefix(3))
    }

    private var hiddenCount: Int {
        max(0, Int(workspaceWithSessions.sessions.count) - 3)
    }

    @Namespace private var glassNamespace

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header row
            HStack(spacing: 10) {
                Image(systemName: "folder.fill")
                    .foregroundStyle(.secondary)
                    .font(.body)

                Text(displayTitle)
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Spacer(minLength: 0)

                if isCreatingSession {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    if #available(iOS 26, *) {
                        Button(action: onCreateSession) {
                            Image(systemName: "plus")
                                .font(.system(.caption, weight: .semibold))
                        }
                        .buttonStyle(.glass)
                    } else {
                        Button(action: onCreateSession) {
                            Image(systemName: "plus")
                                .font(.system(.caption, weight: .semibold))
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .onTapGesture(perform: onToggleExpand)

            // Expanded session list
            if isExpanded {
                if workspaceWithSessions.isLoading {
                    HStack {
                        Spacer()
                        ProgressView()
                            .controlSize(.small)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                } else if let error = workspaceWithSessions.error {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                } else if sessions.isEmpty {
                    Text("No sessions")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(sessions, id: \.id) { session in
                            sessionRow(session)
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }

                        if hiddenCount > 0 && !isFullyExpanded {
                            Button(action: onToggleFullExpand) {
                                Text("View \(hiddenCount) more sessions")
                                    .font(.caption)
                                    .foregroundStyle(.accent)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                        } else if isFullyExpanded && hiddenCount > 0 {
                            Button(action: onToggleFullExpand) {
                                Text("Show less")
                                    .font(.caption)
                                    .foregroundStyle(.accent)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                        }
                    }
                }
            }
        }
        .workspaceCardGlass(isActive: isActive)
        .workspaceCardGlassID(workspaceWithSessions.workspace.projectId, namespace: glassNamespace)
    }

    @ViewBuilder
    private func sessionRow(_ session: Session) -> some View {
        Button {
            onSelectSession(session.id)
        } label: {
            HStack(spacing: 8) {
                if session.id == activeSessionId {
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 6, height: 6)
                } else {
                    Circle()
                        .fill(Color.clear)
                        .frame(width: 6, height: 6)
                }

                Text(session.title ?? session.id.prefix(8).description)
                    .font(.subheadline)
                    .foregroundStyle(session.id == activeSessionId ? .primary : .secondary)
                    .lineLimit(1)

                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Glass modifiers

private extension View {
    @ViewBuilder
    func workspaceCardGlass(isActive: Bool) -> some View {
        if #available(iOS 26, *) {
            let glass: GlassEffect = isActive
                ? .regular.tint(.accentColor).interactive()
                : .regular.interactive()
            self.glassEffect(glass, in: .rect(cornerRadius: 12))
        } else {
            self.background(
                isActive ? Color.accentColor.opacity(0.08) : Color(.secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 12)
            )
        }
    }

    @ViewBuilder
    func workspaceCardGlassID(_ id: String, namespace: Namespace.ID) -> some View {
        if #available(iOS 26, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            self
        }
    }
}
```

- [ ] **Step 2: Verify it compiles by building the iOS target**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build 2>&1 | tail -5`
Expected: BUILD SUCCEEDED (or verify via Xcode)

- [ ] **Step 3: Commit**

```bash
git add iosApp/iosApp/SwiftUIInterop/WorkspaceCardView.swift
git commit -m "feat: add WorkspaceCardView with glass styling and expand/collapse"
```

---

### Task 4: Create WorkspacesSidebarView

**Files:**
- Create: `iosApp/iosApp/SwiftUIInterop/WorkspacesSidebarView.swift`

- [ ] **Step 1: Create the sidebar root view**

This wraps the workspace cards in a `ScrollView` + `LazyVStack` with a toolbar for the "Add Workspace" button.

```swift
import SwiftUI
import ComposeApp

@MainActor
struct WorkspacesSidebarView: View {
    let viewModel: SidebarViewModel
    let onSelectSession: () -> Void
    let onRequestAppReset: () -> Void

    @StateObject private var uiStateEvents = KmpUiEventBridge<SidebarUiState>()
    @State private var latestUiState: SidebarUiState?
    @State private var expanded: Set<String> = []
    @State private var fullyExpanded: Set<String> = []
    @State private var isShowingAddWorkspace = false
    @State private var draftDirectory = ""

    var body: some View {
        Group {
            if let state = latestUiState {
                sidebarContent(state: state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Workspaces")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let state = latestUiState, state.isCreatingWorkspace {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button(action: { isShowingAddWorkspace = true }) {
                        Image(systemName: "plus")
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingAddWorkspace) {
            addWorkspaceSheet
        }
        .onAppear {
            uiStateEvents.start(flow: viewModel.uiState) { state in
                latestUiState = state

                // Auto-expand active workspace on first load
                if expanded.isEmpty, let activeId = state.activeWorkspaceId {
                    expanded.insert(activeId)
                    viewModel.loadSessionsForWorkspace(projectId: activeId)
                }
            }
        }
        .onDisappear {
            uiStateEvents.stop()
        }
        .task(id: latestUiState?.switchedWorkspaceId ?? "") {
            guard let switchedId = latestUiState?.switchedWorkspaceId, !switchedId.isEmpty else { return }
            viewModel.clearWorkspaceSwitch()
            onRequestAppReset()
        }
        .task(id: latestUiState?.createdSessionId ?? "") {
            guard let sessionId = latestUiState?.createdSessionId, !sessionId.isEmpty else { return }
            viewModel.clearCreatedSession()
            // If a workspace switch is also pending, let that handle the reset
            if latestUiState?.switchedWorkspaceId != nil {
                return
            }
            onSelectSession()
        }
    }

    @ViewBuilder
    private func sidebarContent(state: SidebarUiState) -> some View {
        ScrollView {
            glassContainerWrapper {
            LazyVStack(spacing: 12) {
                ForEach(state.workspaces, id: \.workspace.projectId) { workspaceWithSessions in
                    let projectId = workspaceWithSessions.workspace.projectId
                    let isActive = projectId == state.activeWorkspaceId
                    let isExp = expanded.contains(projectId)
                    let isFull = fullyExpanded.contains(projectId)

                    WorkspaceCardView(
                        workspaceWithSessions: workspaceWithSessions,
                        isActive: isActive,
                        activeSessionId: state.activeSessionId,
                        isExpanded: isExp,
                        isFullyExpanded: isFull,
                        isCreatingSession: state.isCreatingSession,
                        onToggleExpand: {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                if expanded.contains(projectId) {
                                    expanded.remove(projectId)
                                } else {
                                    expanded.insert(projectId)
                                    // Load sessions on first expand
                                    if workspaceWithSessions.sessions.isEmpty && !workspaceWithSessions.isLoading {
                                        viewModel.loadSessionsForWorkspace(projectId: projectId)
                                    }
                                }
                            }
                        },
                        onToggleFullExpand: {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                if fullyExpanded.contains(projectId) {
                                    fullyExpanded.remove(projectId)
                                } else {
                                    fullyExpanded.insert(projectId)
                                }
                            }
                        },
                        onSelectSession: { sessionId in
                            if isActive {
                                viewModel.switchSession(sessionId: sessionId)
                                onSelectSession()
                            } else {
                                viewModel.switchWorkspace(projectId: projectId, sessionId: sessionId)
                            }
                        },
                        onCreateSession: {
                            viewModel.createSession(workspaceProjectId: projectId)
                        }
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            }
        }
    }

    @ViewBuilder
    private func glassContainerWrapper<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 12) {
                content()
            }
        } else {
            content()
        }
    }

    @ViewBuilder
    private var addWorkspaceSheet: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Directory path", text: $draftDirectory)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } footer: {
                    Text("Enter the full directory path on the server machine.")
                }
            }
            .navigationTitle("Add Workspace")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        draftDirectory = ""
                        isShowingAddWorkspace = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let trimmed = draftDirectory.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        viewModel.addWorkspace(directoryInput: trimmed)
                        draftDirectory = ""
                        isShowingAddWorkspace = false
                    }
                    .disabled(draftDirectory.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify iOS build compiles**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build 2>&1 | tail -5`
Expected: BUILD SUCCEEDED

- [ ] **Step 3: Commit**

```bash
git add iosApp/iosApp/SwiftUIInterop/WorkspacesSidebarView.swift
git commit -m "feat: add WorkspacesSidebarView with expandable workspace cards"
```

---

## Chunk 3: Navigation & Toolbar Rewiring (Swift)

### Task 5: Modify ChatToolbarGlassView — hamburger + new title/subtitle

**Files:**
- Modify: `iosApp/iosApp/ChatUIKit/ChatScreenChromeView.swift:6-105`
- Modify: `iosApp/iosApp/ChatUIKit/SwiftUIChatUIKitView.swift:6-119`

- [ ] **Step 1: Update `ChatToolbarGlassView` parameters and layout**

In `ChatScreenChromeView.swift`, replace the `ChatToolbarGlassView` struct (lines 6-105):

1. Replace `onOpenSessions` parameter with `onToggleSidebar`:
   - Change line 10 from `let onOpenSessions: () -> Void` to `let onToggleSidebar: () -> Void`

2. Add new parameters after `onRevert` (line 13):
   - `let sessionTitle: String?`
   - `let workspacePath: String?`

3. Replace the `subtitle` computed property (lines 27-32) with:
   ```swift
   private var subtitle: String {
       guard let path = workspacePath, !path.isEmpty else {
           return "Pocket chat"
       }
       let lastComponent = (path as NSString).lastPathComponent
       return lastComponent.isEmpty ? path : "…/\(lastComponent)"
   }
   ```

4. Replace the title `Text("OpenCode")` (lines 45-48) with:
   ```swift
   Text(sessionTitle ?? "OpenCode")
       .font(.system(.title3, design: .rounded).weight(.semibold))
       .foregroundStyle(.primary)
       .lineLimit(1)
   ```

5. Restructure the `HStack` at lines 43-76 so the hamburger is on the left and the sessions button is removed:
   ```swift
   HStack(alignment: .center, spacing: 12) {
       ChatToolbarIconButton(action: onToggleSidebar) {
           Image(systemName: "line.3.horizontal")
       }

       VStack(alignment: .leading, spacing: 3) {
           Text(sessionTitle ?? "OpenCode")
               .font(.system(.title3, design: .rounded).weight(.semibold))
               .foregroundStyle(.primary)
               .lineLimit(1)

           Text(subtitle)
               .font(.system(.caption, design: .monospaced))
               .foregroundStyle(.secondary)
               .lineLimit(1)
       }

       Spacer(minLength: 0)

       HStack(spacing: 8) {
           ChatToolbarIconButton(action: onRetry) {
               if isRefreshing {
                   ProgressView()
                       .controlSize(.small)
               } else {
                   Image(systemName: "arrow.clockwise")
               }
           }
           .disabled(isRefreshing)

           ChatToolbarIconButton(action: onOpenSettings) {
               Image(systemName: "gearshape")
           }
       }
   }
   ```

- [ ] **Step 2: Update `SwiftUIChatUIKitView` to pass new parameters**

In `SwiftUIChatUIKitView.swift`:

1. Replace `onOpenSessions` parameter (line 10) with `onToggleSidebar`:
   - `let onToggleSidebar: () -> Void`

2. Add new parameters:
   - `let sessionTitle: String?`
   - `let workspacePath: String?`

3. Update the `init` (lines 19-31) to accept the new parameters.

4. Update `toolbarOverlay` (lines 104-119) to pass the new parameters to `ChatToolbarGlassView`:
   ```swift
   ChatToolbarGlassView(
       state: state,
       isRefreshing: state.isRefreshing,
       onRetry: viewModel.retry,
       onToggleSidebar: onToggleSidebar,
       onOpenSettings: onOpenSettings,
       onDismissError: viewModel.dismissError,
       onRevert: viewModel.revertToLastGood,
       sessionTitle: sessionTitle,
       workspacePath: workspacePath
   )
   ```

- [ ] **Step 3: Verify iOS build compiles (will fail until Task 6 wires the call site)**

This is expected — the call site in `SwiftUIAppRootView` still passes the old parameters. We fix that in Task 6.

- [ ] **Step 4: Commit**

```bash
git add iosApp/iosApp/ChatUIKit/ChatScreenChromeView.swift \
       iosApp/iosApp/ChatUIKit/SwiftUIChatUIKitView.swift
git commit -m "feat: replace sessions button with hamburger, add session title + workspace path to toolbar"
```

---

### Task 6: Rewire SwiftUIAppRootView — NavigationSplitView + sidebar

**Files:**
- Modify: `iosApp/iosApp/SwiftUIInterop/SwiftUIAppRootView.swift`

- [ ] **Step 1: Update the route enum**

Remove `.workspaces` case from `SwiftUIAppRoute` (line 9). The enum becomes:

```swift
private enum SwiftUIAppRoute: Hashable {
    case connect
    case settings
    case modelSelection
    case markdownFile(path: String, openId: Int64)
}
```

- [ ] **Step 2: Replace `pairedAppView` with `NavigationSplitView`**

Replace the `pairedAppView` function. Key changes:
- Remove `@State private var isShowingSessions: Bool = false` (line 21)
- Add `@State private var sidebarVisibility: NavigationSplitViewVisibility = .detailOnly`
- Replace `NavigationStack(path: $path)` with `NavigationSplitView(columnVisibility: $sidebarVisibility)`
- Add sidebar column with `WorkspacesSidebarView`
- Detail column contains the existing `NavigationStack`
- Remove `.sheet(isPresented: $isShowingSessions)` (lines 112-114)
- Remove `.workspaces` case from `navigationDestination` (lines 90-94)
- Remove `workspacesViewModel` instantiation (line 57)
- Add `sidebarViewModel` instantiation
- Remove `onOpenSessions` from `SwiftUISettingsView` (line 83) and `SwiftUIChatUIKitView` (line 64)
- Pass `onToggleSidebar`, `sessionTitle`, `workspacePath` to `SwiftUIChatUIKitView`
- Remove `onOpenWorkspaces` and `onOpenSessions` from `SwiftUISettingsView`

The updated `pairedAppView` requires two new `@State` properties at the top of `SwiftUIAppRootView`:

```swift
@State private var sidebarVisibility: NavigationSplitViewVisibility = .detailOnly
@State private var settingsUiState: SettingsUiState?
@StateObject private var sidebarUiStateEvents = KmpUiEventBridge<SidebarUiState>()
@State private var sidebarUiState: SidebarUiState?
```

Remove the existing `@State private var isShowingSessions: Bool = false` (line 21).

**Sub-step 2a: Store `settingsUiState` from the existing callback.**
In the existing `settingsUiStateEvents.start(flow:)` callback at line 120, add `self.settingsUiState = uiState` as the first line of the closure, before the theme logic.

**Sub-step 2b: Add a `sidebarUiStateEvents` bridge.**
In the `.onAppear` block, add:
```swift
sidebarUiStateEvents.start(flow: sidebarViewModel.uiState) { state in
    sidebarUiState = state
}
```
In `.onDisappear`, add `sidebarUiStateEvents.stop()`.

**Sub-step 2c: Derive session title from `SidebarUiState`.**
`SettingsUiState` does NOT have a session title field. Instead, derive it from the sidebar state:
- Find the active workspace in `sidebarUiState?.workspaces` using `sidebarUiState?.activeWorkspaceId`
- Find the active session in that workspace's sessions using `sidebarUiState?.activeSessionId`
- Use `session.title` as the toolbar title, falling back to the session ID prefix

Add a computed helper in `SwiftUIAppRootView`:
```swift
private var activeSessionTitle: String? {
    guard let state = sidebarUiState,
          let activeWorkspaceId = state.activeWorkspaceId,
          let activeSessionId = state.activeSessionId,
          let workspace = state.workspaces.first(where: { $0.workspace.projectId == activeWorkspaceId }),
          let session = workspace.sessions.first(where: { $0.id == activeSessionId })
    else { return nil }
    return session.title
}
```

The new `pairedAppView`:

```swift
@ViewBuilder
private func pairedAppView(connectViewModel: ConnectViewModel) -> some View {
    let chatViewModel = kmp.owner.chatViewModel()
    let settingsViewModel = kmp.owner.settingsViewModel()
    let sidebarViewModel = kmp.owner.sidebarViewModel()

    NavigationSplitView(columnVisibility: $sidebarVisibility) {
        WorkspacesSidebarView(
            viewModel: sidebarViewModel,
            onSelectSession: {
                sidebarVisibility = .detailOnly
            },
            onRequestAppReset: { onRequestAppReset() }
        )
    } detail: {
        NavigationStack(path: $path) {
            Group {
                SwiftUIChatUIKitView(
                    viewModel: chatViewModel,
                    onOpenSettings: { path.append(.settings) },
                    onToggleSidebar: {
                        withAnimation {
                            sidebarVisibility = sidebarVisibility == .detailOnly
                                ? .doubleColumn
                                : .detailOnly
                        }
                    },
                    onOpenFile: { openMarkdownFile($0) },
                    sessionTitle: activeSessionTitle,
                    workspacePath: settingsUiState?.activeWorkspaceWorktree
                )
            }
            .navigationDestination(for: SwiftUIAppRoute.self) { route in
                switch route {
                case .connect:
                    SwiftUIConnectToOpenCodeView(
                        viewModel: connectViewModel,
                        onConnected: { onRequestAppReset() },
                        onDisconnected: { onRequestAppReset() }
                    )

                case .settings:
                    SwiftUISettingsView(
                        viewModel: settingsViewModel,
                        onOpenConnect: { path.append(.connect) },
                        onOpenModelSelection: { path.append(.modelSelection) },
                        themeRestartNotice: $showThemeRestartNotice
                    )

                case .modelSelection:
                    SwiftUIModelSelectionView(viewModel: settingsViewModel)

                case .markdownFile(let filePath, let openId):
                    let key = MarkdownRouteKey(path: filePath, openId: openId)
                    if let store = markdownManager.stores[key] {
                        SwiftUIMarkdownFileViewerView(
                            viewModel: store.owner.markdownFileViewerViewModel(path: filePath, openId: openId),
                            onOpenFile: { openMarkdownFile($0) }
                        )
                    } else {
                        SamFullScreenLoadingView(title: "Opening file...")
                            .task {
                                markdownManager.ensureStore(for: key)
                            }
                    }
                }
            }
        }
    }
    .navigationSplitViewStyle(.balanced)
    .onAppear {
        // Retain ALL existing onAppear logic from lines 115-145 of the original file:
        // - IosThemeApplier.apply (line 118)
        // - settingsUiStateEvents.start with theme logic (lines 120-140) — add settingsUiState = uiState as first line
        // - shareEvents.start (lines 142-144)
        // PLUS add:
        sidebarUiStateEvents.start(flow: sidebarViewModel.uiState) { state in
            sidebarUiState = state
        }
    }
    .onDisappear {
        // Retain existing: settingsUiStateEvents.stop(), shareEvents.stop(), pendingThemeVerification cancel
        sidebarUiStateEvents.stop()
    }
    // Retain existing modifiers from lines 152-172:
    // - .onChange(of: path) — markdown store pruning
    // - .onChange(of: scenePhase) — foreground/background handling
}
```

- [ ] **Step 3: Verify iOS build compiles**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build 2>&1 | tail -5`
Expected: BUILD SUCCEEDED

- [ ] **Step 4: Commit**

```bash
git add iosApp/iosApp/SwiftUIInterop/SwiftUIAppRootView.swift
git commit -m "feat: replace NavigationStack with NavigationSplitView, wire sidebar"
```

---

### Task 7: Clean up SwiftUISettingsView

**Files:**
- Modify: `iosApp/iosApp/SwiftUIInterop/SwiftUISettingsViews.swift:4-171`

- [ ] **Step 1: Remove workspace and sessions parameters and rows**

1. Remove `let onOpenWorkspaces: () -> Void` (line 9)
2. Remove `let onOpenSessions: () -> Void` (line 10)
3. Remove the Workspace button (lines 64-79 inside `Section("App")`)
4. Remove the entire `Section("Navigation")` block (lines 167-171)
5. Remove the now-unused `workspaceText(name:worktree:)` private function (around lines 336-349) which becomes dead code after the Workspace row is removed

- [ ] **Step 2: Verify iOS build compiles**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build 2>&1 | tail -5`
Expected: BUILD SUCCEEDED

- [ ] **Step 3: Commit**

```bash
git add iosApp/iosApp/SwiftUIInterop/SwiftUISettingsViews.swift
git commit -m "refactor: remove workspace and sessions navigation from Settings"
```

---

## Chunk 4: Cleanup & Deletion

### Task 8: Delete old files and clean up DI

**Files:**
- Delete: `iosApp/iosApp/SwiftUIInterop/SwiftUISessionsViews.swift`
- Delete: `iosApp/iosApp/SwiftUIInterop/SwiftUIWorkspacesViews.swift`
- Delete: `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModel.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/workspaces/WorkspacesViewModel.kt`
- Delete: `composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModelTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/di/AppModule.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/IosViewModelOwners.kt`

- [ ] **Step 1: Delete the Swift view files**

```bash
rm iosApp/iosApp/SwiftUIInterop/SwiftUISessionsViews.swift
rm iosApp/iosApp/SwiftUIInterop/SwiftUIWorkspacesViews.swift
```

If the Xcode project uses explicit file references in `.pbxproj` for these files, remove the corresponding entries. If using folder references (most likely), no `.pbxproj` changes are needed.

- [ ] **Step 2: Delete the Kotlin ViewModel files and test**

```bash
rm composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModel.kt
rm composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/workspaces/WorkspacesViewModel.kt
rm composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModelTest.kt
```

- [ ] **Step 3: Remove old factory methods from AppModule**

In `AppModule.kt`:
- Remove `createSessionsViewModel()` (lines 263-268)
- Remove `createWorkspacesViewModel()` (lines 289-291)
- Remove the imports for `SessionsViewModel` and `WorkspacesViewModel` (lines 38-40)

- [ ] **Step 4: Remove old accessors from IosViewModelOwners**

In `IosViewModelOwners.kt`:
- Remove `workspacesViewModel()` from `IosAppViewModelOwner` (line 48)
- Remove `sessionsViewModel()` from `IosScreenViewModelOwner` (line 89)
- Remove the imports for `SessionsViewModel` and `WorkspacesViewModel` (lines 15, 17)

- [ ] **Step 5: Verify both Kotlin and iOS builds compile**

Run (in parallel):
- `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && ./gradlew composeApp:compileKotlinJvm`
- `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build 2>&1 | tail -5`

Expected: Both BUILD SUCCESSFUL

- [ ] **Step 6: Run all remaining tests**

Run: `cd /Users/ratulsarna/Developer/Projects/opencode-pocket && ./gradlew composeApp:jvmTest`
Expected: All tests PASS (including the new SidebarViewModelTest)

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/di/AppModule.kt \
       composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/IosViewModelOwners.kt
git rm iosApp/iosApp/SwiftUIInterop/SwiftUISessionsViews.swift \
      iosApp/iosApp/SwiftUIInterop/SwiftUIWorkspacesViews.swift \
      composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModel.kt \
      composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/workspaces/WorkspacesViewModel.kt \
      composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModelTest.kt
git commit -m "refactor: delete old Sessions/Workspaces views, ViewModels, and DI wiring"
```
