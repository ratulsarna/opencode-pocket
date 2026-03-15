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
import kotlin.time.Clock

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

    fun loadSessionsForWorkspace(projectId: String) {
        val workspace = _uiState.value.workspaces.find { it.workspace.projectId == projectId } ?: return
        if (workspace.isLoading) return

        _uiState.update { state ->
            state.copy(workspaces = state.workspaces.map {
                if (it.workspace.projectId == projectId) it.copy(isLoading = true, error = null) else it
            })
        }

        viewModelScope.launch {
            val start = Clock.System.now().toEpochMilliseconds() - DEFAULT_RECENT_WINDOW_MS
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

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.updateCurrentSessionId(sessionId)
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to switch session: ${error.message}")
                }
        }
    }

    fun switchWorkspace(projectId: String, sessionId: String?) {
        if (_uiState.value.isSwitchingWorkspace) return

        _uiState.update { it.copy(isSwitchingWorkspace = true) }

        viewModelScope.launch {
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

    fun createSession(workspaceProjectId: String) {
        if (_uiState.value.isCreatingSession) return
        _uiState.update { it.copy(isCreatingSession = true) }

        viewModelScope.launch {
            val isActiveWorkspace = workspaceProjectId == _uiState.value.activeWorkspaceId

            if (!isActiveWorkspace) {
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
