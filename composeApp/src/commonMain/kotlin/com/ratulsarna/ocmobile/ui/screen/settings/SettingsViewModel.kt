package com.ratulsarna.ocmobile.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.model.AssistantResponsePartVisibility
import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilityPreset
import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilitySettings
import com.ratulsarna.ocmobile.domain.model.Agent
import com.ratulsarna.ocmobile.domain.model.AgentMode
import com.ratulsarna.ocmobile.domain.model.ContextUsage
import com.ratulsarna.ocmobile.domain.model.Provider
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.repository.AgentRepository
import com.ratulsarna.ocmobile.domain.repository.ConnectionState
import com.ratulsarna.ocmobile.domain.repository.ContextUsageRepository
import com.ratulsarna.ocmobile.domain.repository.EventStream
import com.ratulsarna.ocmobile.domain.repository.ModelRepository
import com.ratulsarna.ocmobile.domain.repository.ServerRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.ui.theme.ThemeMode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsViewModel(
    private val eventStream: EventStream,
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
    private val contextUsageRepository: ContextUsageRepository,
    private val serverRepository: ServerRepository,
    private val appSettings: AppSettings,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val workspaceInitMutex = Mutex()
    private var workspaceInitDeferred: Deferred<Result<com.ratulsarna.ocmobile.domain.model.Workspace>>? = null
    private var workspaceInitKey: String? = null

    init {
        observeConnectionState()
        observeActiveServer()
        observeActiveWorkspace()
        observeSelectedAgent()
        observeSelectedModel()
        observeFavoriteModels()
        observeContextUsage()
        observeThemeMode()
        observeAlwaysExpandAssistantParts()
        observeAssistantResponseVisibility()
        loadAgents()
        loadProviders()
    }

    private suspend fun ensureWorkspaceInitialized(): Result<com.ratulsarna.ocmobile.domain.model.Workspace> {
        fun currentKey(): String {
            val serverId = serverRepository.getActiveServerIdSnapshot()?.trim().orEmpty()
            val workspaceId = workspaceRepository.getActiveWorkspaceSnapshot()?.projectId?.trim().orEmpty()
            return "$serverId|$workspaceId"
        }

        val deferred = workspaceInitMutex.withLock {
            val key = currentKey()
            val existing = workspaceInitDeferred
            if (existing != null && workspaceInitKey == key) return@withLock existing

            // Invalidate cached init when server/workspace changes.
            existing?.cancel()
            workspaceInitDeferred = null
            workspaceInitKey = key

            val created = viewModelScope.async {
                try {
                    // Workspace initialization depends on an active server selection. On first launch (or in mock mode),
                    // SettingsViewModel can run before ChatViewModel establishes the default server profile.
                    serverRepository.ensureInitialized().getOrThrow()
                    Result.success(workspaceRepository.ensureInitialized().getOrThrow())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            workspaceInitDeferred = created
            created
        }

        val result = deferred.await()
        if (result.isFailure) {
            // Allow callers to retry after a transient failure (local network permission, etc.)
            workspaceInitMutex.withLock {
                if (workspaceInitDeferred === deferred) {
                    workspaceInitDeferred = null
                    workspaceInitKey = null
                }
            }
        }
        return result
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            eventStream.connectionState.collect { state ->
                _uiState.update {
                    it.copy(isConnected = state == ConnectionState.CONNECTED)
                }
            }
        }
    }

    private fun observeActiveServer() {
        viewModelScope.launch {
            combine(
                serverRepository.getServers(),
                serverRepository.getActiveServerId()
            ) { servers, activeId ->
                servers.firstOrNull { it.id == activeId }
            }.collect { active ->
                _uiState.update {
                    it.copy(
                        activeServerName = active?.name,
                        activeServerBaseUrl = active?.baseUrl
                    )
                }
            }
        }
    }

    private fun observeActiveWorkspace() {
        viewModelScope.launch {
            workspaceRepository.getActiveWorkspace().collect { active ->
                _uiState.update {
                    it.copy(
                        activeWorkspaceName = active?.name,
                        activeWorkspaceWorktree = active?.worktree
                    )
                }
            }
        }
    }

    private fun observeSelectedAgent() {
        viewModelScope.launch {
            agentRepository.getSelectedAgent().collect { agentName ->
                _uiState.update { it.copy(selectedAgentName = agentName) }
            }
        }
    }

    private fun observeSelectedModel() {
        viewModelScope.launch {
            modelRepository.getSelectedModel().collect { selectedModel ->
                _uiState.update { it.copy(selectedModel = selectedModel) }
            }
        }
    }

    private fun observeFavoriteModels() {
        viewModelScope.launch {
            modelRepository.getFavoriteModels().collect { favorites ->
                _uiState.update { it.copy(favoriteModels = favorites) }
            }
        }
    }

    private fun observeContextUsage() {
        viewModelScope.launch {
            contextUsageRepository.contextUsage.collect { usage ->
                _uiState.update { it.copy(contextUsage = usage) }
            }
        }
    }

    private fun observeThemeMode() {
        viewModelScope.launch {
            appSettings.getThemeMode().collect { mode ->
                _uiState.update { it.copy(selectedThemeMode = mode) }
            }
        }
    }

    private fun observeAlwaysExpandAssistantParts() {
        viewModelScope.launch {
            appSettings.getAlwaysExpandAssistantParts().collect { alwaysExpand ->
                _uiState.update { it.copy(alwaysExpandAssistantParts = alwaysExpand) }
            }
        }
    }

    private fun observeAssistantResponseVisibility() {
        viewModelScope.launch {
            appSettings.getAssistantResponseVisibility().collect { visibility ->
                _uiState.update { it.copy(assistantResponseVisibility = visibility) }
            }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAgents = true, agentError = null) }

            // Ensure an active workspace so directory-scoped endpoints don't fail on first load after pairing.
            val workspaceResult = ensureWorkspaceInitialized()
            if (workspaceResult.isFailure) {
                val message = workspaceResult.exceptionOrNull()?.message ?: "Failed to initialize workspace"
                _uiState.update { it.copy(isLoadingAgents = false, agentError = message) }
                return@launch
            }

            agentRepository.getAgents()
                .onSuccess { agents ->
                    // Filter to show only PRIMARY mode agents
                    val primaryAgents = agents.filter { it.mode == AgentMode.PRIMARY }
                    _uiState.update {
                        it.copy(agents = primaryAgents, isLoadingAgents = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingAgents = false,
                            agentError = error.message ?: "Failed to load agents"
                        )
                    }
                }
        }
    }

    private fun loadProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, modelError = null) }

            // Ensure an active workspace so directory-scoped endpoints don't fail on first load after pairing.
            val workspaceResult = ensureWorkspaceInitialized()
            if (workspaceResult.isFailure) {
                val message = workspaceResult.exceptionOrNull()?.message ?: "Failed to initialize workspace"
                _uiState.update { it.copy(isLoadingModels = false, modelError = message) }
                return@launch
            }

            modelRepository.getConnectedProviders()
                .onSuccess { providers ->
                    _uiState.update {
                        it.copy(providers = providers, isLoadingModels = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingModels = false,
                            modelError = error.message ?: "Failed to load models"
                        )
                    }
                }
        }
    }

    fun selectAgent(agentName: String?) {
        viewModelScope.launch {
            agentRepository.setSelectedAgent(agentName)
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            modelRepository.setSelectedModel(SelectedModel(providerId, modelId))
        }
    }

    fun toggleFavoriteModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            modelRepository.toggleFavoriteModel(SelectedModel(providerId, modelId))
        }
    }

    fun updateModelSearchQuery(query: String) {
        _uiState.update { it.copy(modelSearchQuery = query) }
    }

    fun refreshAgents() {
        loadAgents()
    }

    fun refreshModels() {
        loadProviders()
    }

    fun selectThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            appSettings.setThemeMode(mode)
        }
    }

    fun setAlwaysExpandAssistantParts(alwaysExpand: Boolean) {
        viewModelScope.launch {
            appSettings.setAlwaysExpandAssistantParts(alwaysExpand)
        }
    }

    fun setAssistantResponsePreset(preset: AssistantResponseVisibilityPreset) {
        viewModelScope.launch {
            val current = _uiState.value.assistantResponseVisibility
            val updated = if (preset == AssistantResponseVisibilityPreset.CUSTOM) {
                current.copy(
                    preset = preset,
                    custom = current.custom ?: current.effective()
                )
            } else {
                current.copy(preset = preset)
            }
            appSettings.setAssistantResponseVisibility(updated)
        }
    }

    fun setAssistantResponsePresetName(name: String) {
        val preset = try {
            AssistantResponseVisibilityPreset.valueOf(name)
        } catch (_: Exception) {
            AssistantResponseVisibilityPreset.DEFAULT
        }
        setAssistantResponsePreset(preset)
    }

    fun setAssistantResponseCustomVisibility(custom: AssistantResponsePartVisibility) {
        viewModelScope.launch {
            val current = _uiState.value.assistantResponseVisibility
            appSettings.setAssistantResponseVisibility(
                current.copy(
                    preset = AssistantResponseVisibilityPreset.CUSTOM,
                    custom = custom
                )
            )
        }
    }

    fun setAssistantResponseShowThinking(show: Boolean) {
        setAssistantResponseCustomFlag { it.copy(showReasoning = show) }
    }

    fun setAssistantResponseShowTools(show: Boolean) {
        setAssistantResponseCustomFlag { it.copy(showTools = show) }
    }

    fun setAssistantResponseShowPatches(show: Boolean) {
        setAssistantResponseCustomFlag { it.copy(showPatches = show) }
    }

    fun setAssistantResponseShowAgentDelegation(show: Boolean) {
        setAssistantResponseCustomFlag { it.copy(showAgents = show) }
    }

    fun setAssistantResponseShowRetries(show: Boolean) {
        setAssistantResponseCustomFlag { it.copy(showRetries = show) }
    }

    fun setAssistantResponseShowCompaction(show: Boolean) {
        setAssistantResponseCustomFlag { it.copy(showCompactions = show) }
    }

    fun setAssistantResponseShowUnknownParts(show: Boolean) {
        setAssistantResponseCustomFlag { it.copy(showUnknowns = show) }
    }

    private fun setAssistantResponseCustomFlag(
        update: (AssistantResponsePartVisibility) -> AssistantResponsePartVisibility
    ) {
        viewModelScope.launch {
            val currentSettings = _uiState.value.assistantResponseVisibility
            val base = currentSettings.custom ?: currentSettings.effective()
            val updated = update(base)
            appSettings.setAssistantResponseVisibility(
                currentSettings.copy(
                    preset = AssistantResponseVisibilityPreset.CUSTOM,
                    custom = updated
                )
            )
        }
    }

    /**
     * Manual refresh - kept for UI compatibility but now effectively a no-op
     * since connection state is observed reactively.
     */
    fun refreshConnectionStatus() {
        // Connection state is now observed reactively via connectionState Flow
    }
}

data class SettingsUiState(
    val isConnected: Boolean = false,
    // Server selection
    val activeServerName: String? = null,
    val activeServerBaseUrl: String? = null,
    // Workspace selection
    val activeWorkspaceName: String? = null,
    val activeWorkspaceWorktree: String? = null,
    // Context usage
    val contextUsage: ContextUsage = ContextUsage.UNKNOWN,
    // Agent selection
    val agents: List<Agent> = emptyList(),
    val selectedAgentName: String? = null,
    val isLoadingAgents: Boolean = false,
    val agentError: String? = null,
    // Model selection
    val providers: List<Provider> = emptyList(),
    val selectedModel: SelectedModel? = null,
    val favoriteModels: List<SelectedModel> = emptyList(),
    val isLoadingModels: Boolean = false,
    val modelError: String? = null,
    val modelSearchQuery: String = "",
    // Theme selection
    val selectedThemeMode: ThemeMode = ThemeMode.SYSTEM,
    // Developer settings
    val alwaysExpandAssistantParts: Boolean = AppSettings.DEFAULT_ALWAYS_EXPAND_ASSISTANT_PARTS,
    val assistantResponseVisibility: AssistantResponseVisibilitySettings = AssistantResponseVisibilitySettings()
)
