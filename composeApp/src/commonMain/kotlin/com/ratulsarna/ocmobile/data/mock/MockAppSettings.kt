package com.ratulsarna.ocmobile.data.mock

import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilitySettings
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.model.ServerProfile
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mock implementation of AppSettings for testing.
 * Uses in-memory storage (does not persist across app restarts).
 */
class MockAppSettings : AppSettings {

    private val serverProfiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    private val activeServerId = MutableStateFlow<String?>(null)
    private val installationIdByServer = mutableMapOf<String, String>()
    private val installationIdForActiveServer = MutableStateFlow<String?>(null)
    private val authTokenByServer = mutableMapOf<String, String?>()
    private val authTokenForActiveServer = MutableStateFlow<String?>(null)

    private val workspacesByInstallation = mutableMapOf<String, List<Workspace>>()
    private val activeWorkspaceByInstallation = mutableMapOf<String, Workspace?>()
    private val workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private val activeWorkspace = MutableStateFlow<Workspace?>(null)

    private val currentSessionIdByInstallationProject = mutableMapOf<String, String?>()
    private val currentSessionIdByInstallation = mutableMapOf<String, String?>()
    private var legacyCurrentSessionId: String? = null
    private val currentSessionId = MutableStateFlow<String?>(null)
    private val selectedAgent = MutableStateFlow<String?>(null)
    private val selectedModel = MutableStateFlow<SelectedModel?>(null)
    private val favoriteModels = MutableStateFlow<List<SelectedModel>>(emptyList())
    private val themeMode = MutableStateFlow(AppSettings.DEFAULT_THEME_MODE)
    private val alwaysExpandAssistantParts = MutableStateFlow(AppSettings.DEFAULT_ALWAYS_EXPAND_ASSISTANT_PARTS)
    private val assistantResponseVisibility = MutableStateFlow(AssistantResponseVisibilitySettings())
    private val thinkingVariantsByModel = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun getCurrentSessionId(): Flow<String?> {
        return currentSessionId.asStateFlow()
    }

    override fun getCurrentSessionIdSnapshot(): String? {
        return currentSessionId.value
    }

    override suspend fun setCurrentSessionId(sessionId: String?) {
        val inst = installationIdForActiveServer.value
        val projectId = activeWorkspace.value?.projectId

        if (inst.isNullOrBlank()) {
            legacyCurrentSessionId = sessionId
        } else if (projectId.isNullOrBlank()) {
            currentSessionIdByInstallation[inst] = sessionId
        } else {
            currentSessionIdByInstallationProject["$inst:$projectId"] = sessionId
        }
        currentSessionId.value = sessionId
    }

    override fun getInstallationIdForActiveServer(): Flow<String?> {
        return installationIdForActiveServer.asStateFlow()
    }

    override fun getInstallationIdForActiveServerSnapshot(): String? {
        return installationIdForActiveServer.value
    }

    override suspend fun setInstallationIdForServer(serverId: String, installationId: String) {
        val sid = serverId.trim()
        val inst = installationId.trim()
        if (sid.isBlank() || inst.isBlank()) return

        installationIdByServer[sid] = inst
        if (activeServerId.value == sid) {
            installationIdForActiveServer.value = inst
            workspaces.value = workspacesByInstallation[inst].orEmpty()
            activeWorkspace.value = activeWorkspaceByInstallation[inst]
            val projectId = activeWorkspace.value?.projectId
            currentSessionId.value = if (projectId.isNullOrBlank()) {
                currentSessionIdByInstallation[inst]
            } else {
                currentSessionIdByInstallationProject["$inst:$projectId"]
            }
        }
    }

    override fun getWorkspaces(): Flow<List<Workspace>> {
        return workspaces.asStateFlow()
    }

    override fun getWorkspacesSnapshot(): List<Workspace> {
        return workspaces.value
    }

    override suspend fun setWorkspacesForInstallation(installationId: String, workspaces: List<Workspace>) {
        val inst = installationId.trim()
        if (inst.isBlank()) return
        workspacesByInstallation[inst] = workspaces
        if (installationIdForActiveServer.value == inst) {
            this.workspaces.value = workspaces
        }
    }

    override fun getActiveWorkspace(): Flow<Workspace?> {
        return activeWorkspace.asStateFlow()
    }

    override fun getActiveWorkspaceSnapshot(): Workspace? {
        return activeWorkspace.value
    }

    override suspend fun setActiveWorkspace(installationId: String, workspace: Workspace?) {
        val inst = installationId.trim()
        if (inst.isBlank()) return
        activeWorkspaceByInstallation[inst] = workspace
        if (installationIdForActiveServer.value == inst) {
            activeWorkspace.value = workspace
            val projectId = workspace?.projectId
            currentSessionId.value = if (projectId.isNullOrBlank()) {
                currentSessionIdByInstallation[inst]
            } else {
                currentSessionIdByInstallationProject["$inst:$projectId"]
            }
        }
    }

    override fun getServerProfiles(): Flow<List<ServerProfile>> {
        return serverProfiles.asStateFlow()
    }

    override fun getServerProfilesSnapshot(): List<ServerProfile> {
        return serverProfiles.value
    }

    override suspend fun setServerProfiles(profiles: List<ServerProfile>) {
        serverProfiles.value = profiles
        val active = activeServerId.value
        if (!active.isNullOrBlank() && profiles.none { it.id == active }) {
            activeServerId.value = null
        }
    }

    override fun getActiveServerId(): Flow<String?> {
        return activeServerId.asStateFlow()
    }

    override fun getActiveServerIdSnapshot(): String? {
        return activeServerId.value
    }

    override suspend fun setActiveServerId(serverId: String?) {
        activeServerId.value = serverId

        val inst = serverId?.let { installationIdByServer[it] }
        installationIdForActiveServer.value = inst
        authTokenForActiveServer.value = serverId?.let { authTokenByServer[it] }
        workspaces.value = if (inst == null) emptyList() else workspacesByInstallation[inst].orEmpty()
        activeWorkspace.value = if (inst == null) null else activeWorkspaceByInstallation[inst]

        val projectId = activeWorkspace.value?.projectId
        currentSessionId.value = if (inst.isNullOrBlank()) {
            legacyCurrentSessionId
        } else if (projectId.isNullOrBlank()) {
            currentSessionIdByInstallation[inst]
        } else {
            currentSessionIdByInstallationProject["$inst:$projectId"]
        }
    }

    override fun getAuthTokenForActiveServer(): Flow<String?> {
        return authTokenForActiveServer.asStateFlow()
    }

    override fun getAuthTokenForActiveServerSnapshot(): String? {
        return authTokenForActiveServer.value
    }

    override suspend fun resetConnectionState() {
        serverProfiles.value = emptyList()
        activeServerId.value = null

        installationIdByServer.clear()
        authTokenByServer.clear()
        installationIdForActiveServer.value = null
        authTokenForActiveServer.value = null

        workspacesByInstallation.clear()
        activeWorkspaceByInstallation.clear()
        workspaces.value = emptyList()
        activeWorkspace.value = null

        currentSessionIdByInstallationProject.clear()
        currentSessionIdByInstallation.clear()
        legacyCurrentSessionId = null
        currentSessionId.value = null

        selectedAgent.value = null
        selectedModel.value = null
        favoriteModels.value = emptyList()
        thinkingVariantsByModel.value = emptyMap()
    }

    override suspend fun setAuthTokenForServer(serverId: String, token: String?) {
        val sid = serverId.trim()
        if (sid.isBlank()) return
        val normalized = token?.trim()?.takeIf { it.isNotBlank() }
        authTokenByServer[sid] = normalized
        if (activeServerId.value == sid) {
            authTokenForActiveServer.value = normalized
        }
    }

    override fun getSelectedAgent(): Flow<String?> {
        return selectedAgent.asStateFlow()
    }

    override suspend fun setSelectedAgent(agentName: String?) {
        selectedAgent.value = agentName
    }

    override fun getSelectedModel(): Flow<SelectedModel?> {
        return selectedModel.asStateFlow()
    }

    override fun getSelectedModelSnapshot(): SelectedModel? {
        return selectedModel.value
    }

    override suspend fun setSelectedModel(model: SelectedModel?) {
        selectedModel.value = model
    }

    override fun getFavoriteModels(): Flow<List<SelectedModel>> {
        return favoriteModels.asStateFlow()
    }

    override fun getFavoriteModelsSnapshot(): List<SelectedModel> {
        return favoriteModels.value
    }

    override suspend fun setFavoriteModels(models: List<SelectedModel>) {
        favoriteModels.value = models
    }

    override fun getThemeMode(): Flow<ThemeMode> {
        return themeMode.asStateFlow()
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }

    override fun getAlwaysExpandAssistantParts(): Flow<Boolean> {
        return alwaysExpandAssistantParts.asStateFlow()
    }

    override suspend fun setAlwaysExpandAssistantParts(alwaysExpand: Boolean) {
        alwaysExpandAssistantParts.value = alwaysExpand
    }

    override fun getAssistantResponseVisibility(): Flow<AssistantResponseVisibilitySettings> {
        return assistantResponseVisibility.asStateFlow()
    }

    override suspend fun setAssistantResponseVisibility(value: AssistantResponseVisibilitySettings) {
        assistantResponseVisibility.value = value
    }

    override fun getThinkingVariantsByModel(): Flow<Map<String, String>> {
        return thinkingVariantsByModel.asStateFlow()
    }

    override suspend fun setThinkingVariantForModel(providerId: String, modelId: String, variant: String?) {
        val key = "${providerId.trim()}:${modelId.trim()}"
        val next = thinkingVariantsByModel.value.toMutableMap()
        if (variant.isNullOrBlank()) {
            next.remove(key)
        } else {
            next[key] = variant.trim()
        }
        thinkingVariantsByModel.value = next.toMap()
    }
}
