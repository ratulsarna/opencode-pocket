package com.ratulsarna.ocmobile.ui.screen.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.util.OcMobileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WorkspacesVM"

class WorkspacesViewModel(
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspacesUiState())
    val uiState: StateFlow<WorkspacesUiState> = _uiState.asStateFlow()

    init {
        ensureInitialized()
        observeWorkspaces()
    }

    private fun ensureInitialized() {
        viewModelScope.launch {
            workspaceRepository.ensureInitialized()
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to initialize workspaces: ${error.message}")
                    _uiState.update { it.copy(error = error.message ?: "Failed to initialize workspaces") }
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
                        workspaces = workspaces,
                        activeProjectId = active?.projectId,
                        error = null
                    )
                }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            workspaceRepository.refresh()
                .onSuccess {
                    _uiState.update { it.copy(isRefreshing = false, error = null) }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to refresh workspaces: ${error.message}")
                    _uiState.update { it.copy(isRefreshing = false, error = error.message ?: "Failed to refresh workspaces") }
                }
        }
    }

    fun addWorkspace(directoryInput: String) {
        var shouldStart = false
        _uiState.update { state ->
            if (state.isSaving) {
                state
            } else {
                shouldStart = true
                state.copy(isSaving = true, error = null)
            }
        }
        if (!shouldStart) return

        viewModelScope.launch {
            workspaceRepository.addWorkspace(directoryInput)
                .onSuccess {
                    _uiState.update { it.copy(isSaving = false, error = null) }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to add workspace: ${error.message}")
                    _uiState.update { it.copy(isSaving = false, error = error.message ?: "Failed to add workspace") }
                }
        }
    }

    fun activateWorkspace(projectId: String) {
        var shouldStart = false
        _uiState.update { state ->
            if (state.isActivating) {
                state
            } else {
                shouldStart = true
                state.copy(isActivating = true, activatingProjectId = projectId, activationError = null)
            }
        }
        if (!shouldStart) return

        viewModelScope.launch {
            workspaceRepository.activateWorkspace(projectId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isActivating = false,
                            activatingProjectId = null,
                            activatedProjectId = projectId
                        )
                    }
                }
                .onFailure { error ->
                    OcMobileLog.w(TAG, "Failed to activate workspace: ${error.message}")
                    _uiState.update {
                        it.copy(
                            isActivating = false,
                            activatingProjectId = null,
                            activationError = error.message ?: "Failed to switch workspace"
                        )
                    }
                }
        }
    }

    fun clearActivation() {
        _uiState.update {
            it.copy(
                isActivating = false,
                activatingProjectId = null,
                activatedProjectId = null,
                activationError = null
            )
        }
    }
}

data class WorkspacesUiState(
    val workspaces: List<Workspace> = emptyList(),
    val activeProjectId: String? = null,
    val isRefreshing: Boolean = false,
    val isSaving: Boolean = false,
    val isActivating: Boolean = false,
    val activatingProjectId: String? = null,
    val activatedProjectId: String? = null,
    val error: String? = null,
    val activationError: String? = null
)
