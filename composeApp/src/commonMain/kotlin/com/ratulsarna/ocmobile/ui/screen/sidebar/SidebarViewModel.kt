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
    private val loadingWorkspaceIds = mutableSetOf<String>()
    val uiState: StateFlow<SidebarUiState> = _uiState.asStateFlow()

    init {
        ensureInitialized()
        observeWorkspaces()
        observeActiveSessionId()
    }

    private fun ensureInitialized() {
        viewModelScope.launch {
            workspaceRepository.ensureInitialized()
                .onSuccess {
                    workspaceRepository.refresh()
                        .onFailure { error ->
                            OcMobileLog.w(TAG, "Failed to refresh workspaces after initialization: ${error.message}")
                        }
                }
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
                _uiState.update { current ->
                    current.copy(
                        activeWorkspaceId = active?.projectId,
                        workspaces = workspaces.map { incoming ->
                            val existing = current.workspaces.find { w -> w.workspace.projectId == incoming.projectId }
                            existing?.copy(workspace = incoming) ?: WorkspaceWithSessions(workspace = incoming)
                        }
                    )
                }

                active?.projectId?.let(::loadSessionsForWorkspace)
            }
        }
    }

    private fun observeActiveSessionId() {
        viewModelScope.launch {
            appSettings.getCurrentSessionId().collect { id ->
                _uiState.update {
                    it.copy(
                        activeSessionId = id,
                        activeSessionTitle = null
                    )
                }

                loadActiveSessionTitle(sessionId = id)
            }
        }
    }

    private fun loadActiveSessionTitle(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            _uiState.update { it.copy(activeSessionTitle = null) }
            return
        }

        viewModelScope.launch {
            sessionRepository.getSession(sessionId)
                .onSuccess { session ->
                    if (sessionId != _uiState.value.activeSessionId) return@onSuccess

                    _uiState.update {
                        it.copy(activeSessionTitle = session.title?.takeIf(String::isNotBlank))
                    }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to load active session title for $sessionId: ${error.message}")
                }
        }
    }

    fun loadSessionsForWorkspace(projectId: String) {
        val workspace = _uiState.value.workspaces.find { it.workspace.projectId == projectId } ?: return
        if (!loadingWorkspaceIds.add(projectId)) return

        // If sessions are already cached, show them immediately and refresh in background.
        // Only show loading indicator on first fetch (no cached sessions).
        val hasCachedSessions = workspace.sessions.isNotEmpty()
        if (!hasCachedSessions) {
            _uiState.update { state ->
                state.copy(workspaces = state.workspaces.map {
                    if (it.workspace.projectId == projectId) {
                        it.copy(isLoading = true, error = null)
                    } else {
                        it
                    }
                })
            }
        }

        viewModelScope.launch {
            try {
                val start = Clock.System.now().toEpochMilliseconds() - DEFAULT_RECENT_WINDOW_MS
                sessionRepository.getSessions(
                    search = null,
                    limit = null,
                    start = start,
                    directory = workspace.workspace.worktree
                )
                    .onSuccess { allSessions ->
                        val filtered = allSessions
                            .filter { it.parentId == null && it.directory == workspace.workspace.worktree }
                            .sortedByDescending { it.updatedAt }
                        _uiState.update { state ->
                            state.copy(workspaces = state.workspaces.map {
                                if (it.workspace.projectId == projectId) {
                                    it.copy(sessions = filtered, isLoading = false, error = null)
                                } else it
                            })
                        }
                    }
                    .onFailure { error ->
                        OcMobileLog.w(TAG, "Failed to load sessions for $projectId: ${error.message}")
                        _uiState.update { state ->
                            state.copy(workspaces = state.workspaces.map {
                                if (it.workspace.projectId == projectId) {
                                    it.copy(
                                        isLoading = false,
                                        error = error.message ?: "Failed to load sessions"
                                    )
                                } else it
                            })
                        }
                    }
            } finally {
                loadingWorkspaceIds.remove(projectId)
            }
        }
    }

    fun switchSession(sessionId: String) {
        if (_uiState.value.isSwitchingSession) return

        _uiState.update {
            it.copy(
                isSwitchingSession = true,
                operationErrorMessage = null,
                switchedSessionId = null
            )
        }

        viewModelScope.launch {
            sessionRepository.updateCurrentSessionId(sessionId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isSwitchingSession = false,
                            switchedSessionId = sessionId
                        )
                    }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to switch session: ${error.message}")
                    _uiState.update {
                        it.copy(
                            isSwitchingSession = false,
                            operationErrorMessage = error.message ?: "Failed to switch session"
                        )
                    }
                }
        }
    }

    fun switchWorkspace(projectId: String, sessionId: String?) {
        if (_uiState.value.isSwitchingWorkspace) return

        _uiState.update { it.copy(isSwitchingWorkspace = true, operationErrorMessage = null) }

        viewModelScope.launch {
            workspaceRepository.activateWorkspace(projectId)
                .onSuccess {
                    if (sessionId != null) {
                        sessionRepository.updateCurrentSessionId(sessionId)
                            .onSuccess {
                                _uiState.update {
                                    it.copy(isSwitchingWorkspace = false, switchedWorkspaceId = projectId)
                                }
                            }
                            .onFailure { error ->
                                OcMobileLog.w(TAG, "Failed to persist session $sessionId for workspace $projectId: ${error.message}")
                                _uiState.update {
                                    it.copy(
                                        isSwitchingWorkspace = false,
                                        operationErrorMessage = error.message ?: "Failed to open session"
                                    )
                                }
                            }
                    } else {
                        _uiState.update { it.copy(isSwitchingWorkspace = false, switchedWorkspaceId = projectId) }
                    }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to switch workspace: ${error.message}")
                    _uiState.update {
                        it.copy(
                            isSwitchingWorkspace = false,
                            operationErrorMessage = error.message ?: "Failed to switch workspace"
                        )
                    }
                }
        }
    }

    fun clearWorkspaceSwitch() {
        _uiState.update { it.copy(switchedWorkspaceId = null) }
    }

    fun clearSwitchedSession() {
        _uiState.update { it.copy(switchedSessionId = null) }
    }

    fun clearOperationError() {
        _uiState.update { it.copy(operationErrorMessage = null) }
    }

    fun createSession(workspaceProjectId: String) {
        if (_uiState.value.isCreatingSession) return
        _uiState.update { it.copy(isCreatingSession = true, operationErrorMessage = null) }

        viewModelScope.launch {
            val isActiveWorkspace = workspaceProjectId == _uiState.value.activeWorkspaceId

            if (!isActiveWorkspace) {
                workspaceRepository.activateWorkspace(workspaceProjectId)
                    .onFailure { error ->
                        OcMobileLog.w(TAG, "Failed to switch workspace for new session: ${error.message}")
                        _uiState.update {
                            it.copy(
                                isCreatingSession = false,
                                operationErrorMessage = error.message ?: "Failed to switch workspace"
                            )
                        }
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
                    _uiState.update {
                        it.copy(
                            isCreatingSession = false,
                            operationErrorMessage = error.message ?: "Failed to create session"
                        )
                    }
                }
        }
    }

    fun clearCreatedSession() {
        _uiState.update { it.copy(createdSessionId = null) }
    }

    fun addWorkspace(directoryInput: String) {
        if (_uiState.value.isCreatingWorkspace) return
        _uiState.update { it.copy(isCreatingWorkspace = true, workspaceCreationError = null) }

        viewModelScope.launch {
            workspaceRepository.addWorkspace(directoryInput)
                .onSuccess {
                    _uiState.update { it.copy(isCreatingWorkspace = false, workspaceCreationError = null) }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to add workspace: ${error.message}")
                    _uiState.update {
                        it.copy(
                            isCreatingWorkspace = false,
                            workspaceCreationError = error.message ?: "Failed to add workspace"
                        )
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            workspaceRepository.refresh()
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to refresh workspaces: ${error.message}")
                }
        }
    }
}

data class SidebarUiState(
    val workspaces: List<WorkspaceWithSessions> = emptyList(),
    val activeWorkspaceId: String? = null,
    val activeSessionId: String? = null,
    val activeSessionTitle: String? = null,
    val isCreatingSession: Boolean = false,
    val isCreatingWorkspace: Boolean = false,
    val workspaceCreationError: String? = null,
    val operationErrorMessage: String? = null,
    val isSwitchingSession: Boolean = false,
    val isSwitchingWorkspace: Boolean = false,
    val switchedSessionId: String? = null,
    val switchedWorkspaceId: String? = null,
    val createdSessionId: String? = null
)

data class WorkspaceWithSessions(
    val workspace: Workspace,
    val sessions: List<Session> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
