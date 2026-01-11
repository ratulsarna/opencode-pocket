package com.ratulsarna.ocmobile.data.settings

import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilitySettings
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.model.ServerProfile
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.ui.theme.ThemeMode
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Implementation of AppSettings using multiplatform-settings library.
 * Uses MutableStateFlow for reactive updates within the session.
 */
class AppSettingsImpl(
    private val settings: Settings = Settings()
) : AppSettings {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Internal state flow to broadcast changes within the app session
    private val _activeServerId = MutableStateFlow(
        settings.getStringOrNull(KEY_ACTIVE_SERVER_ID)
    )

    private val _serverProfiles = MutableStateFlow(
        parseServerProfiles(settings.getStringOrNull(KEY_SERVER_PROFILES))
    )

    private val _installationIdForActiveServer = MutableStateFlow(
        readInstallationIdForServer(_activeServerId.value)
    )

    private val _authTokenForActiveServer = MutableStateFlow(
        readAuthTokenForServer(_activeServerId.value)
    )

    private val _workspaces = MutableStateFlow(
        parseWorkspaces(settings.getStringOrNull(keyForWorkspaces(_installationIdForActiveServer.value)))
    )

    private val _activeWorkspace = MutableStateFlow(
        parseWorkspace(settings.getStringOrNull(keyForActiveWorkspace(_installationIdForActiveServer.value)))
    )

    private val _currentSessionId = MutableStateFlow(
        settings.getStringOrNull(
            keyForCurrentSessionId(
                installationId = _installationIdForActiveServer.value,
                projectId = _activeWorkspace.value?.projectId
            )
        )
    )

    private val _selectedAgent = MutableStateFlow(
        settings.getStringOrNull(KEY_SELECTED_AGENT)
    )

    private val _selectedModel = MutableStateFlow(
        parseSelectedModel(settings.getStringOrNull(KEY_SELECTED_MODEL))
    )

    private val _favoriteModels = MutableStateFlow(
        parseFavoriteModels(settings.getStringOrNull(KEY_FAVORITE_MODELS))
    )

    private val _themeMode = MutableStateFlow(
        parseThemeMode(settings.getStringOrNull(KEY_THEME_MODE))
    )

    private val _alwaysExpandAssistantParts = MutableStateFlow(
        settings.getBoolean(KEY_ALWAYS_EXPAND_ASSISTANT_PARTS, AppSettings.DEFAULT_ALWAYS_EXPAND_ASSISTANT_PARTS)
    )

    private val _assistantResponseVisibility = MutableStateFlow(
        parseAssistantResponseVisibility(settings.getStringOrNull(KEY_ASSISTANT_RESPONSE_VISIBILITY))
    )

    private val _thinkingVariantsByModel = MutableStateFlow(
        parseThinkingVariantsByModel(settings.getStringOrNull(KEY_THINKING_VARIANTS_BY_MODEL))
    )

    override fun getCurrentSessionId(): Flow<String?> {
        return _currentSessionId.asStateFlow()
    }

    override fun getCurrentSessionIdSnapshot(): String? {
        return _currentSessionId.value
    }

    override suspend fun setCurrentSessionId(sessionId: String?) {
        val key = keyForCurrentSessionId(
            installationId = _installationIdForActiveServer.value,
            projectId = _activeWorkspace.value?.projectId
        )

        if (sessionId.isNullOrBlank()) {
            settings.remove(key)
            _currentSessionId.value = null
            return
        }

        settings.putString(key, sessionId)
        _currentSessionId.value = sessionId
    }

    override fun getInstallationIdForActiveServer(): Flow<String?> {
        return _installationIdForActiveServer.asStateFlow()
    }

    override fun getInstallationIdForActiveServerSnapshot(): String? {
        return _installationIdForActiveServer.value
    }

    override suspend fun setInstallationIdForServer(serverId: String, installationId: String) {
        val sid = serverId.trim()
        val inst = installationId.trim()
        if (sid.isBlank() || inst.isBlank()) return

        settings.putString("$KEY_INSTALLATION_ID_BY_SERVER_PREFIX$sid", inst)

        if (_activeServerId.value == sid) {
            updateDerivedWorkspaceState(activeServerId = sid, installationId = inst)
        }
    }

    override fun getWorkspaces(): Flow<List<Workspace>> {
        return _workspaces.asStateFlow()
    }

    override fun getWorkspacesSnapshot(): List<Workspace> {
        return _workspaces.value
    }

    override suspend fun setWorkspacesForInstallation(installationId: String, workspaces: List<Workspace>) {
        val inst = installationId.trim()
        if (inst.isBlank()) return

        val normalized = workspaces
            .asSequence()
            .mapNotNull { workspace ->
                val projectId = workspace.projectId.trim()
                val worktree = workspace.worktree.trim()
                if (projectId.isBlank() || worktree.isBlank()) return@mapNotNull null
                Workspace(
                    projectId = projectId,
                    worktree = worktree,
                    name = workspace.name?.trim()?.takeIf { it.isNotBlank() },
                    lastUsedAtMs = workspace.lastUsedAtMs
                )
            }
            .distinctBy { it.projectId }
            .toList()

        val key = "$KEY_WORKSPACES_BY_INSTALLATION_PREFIX$inst"
        if (normalized.isEmpty()) {
            settings.remove(key)
        } else {
            val records = normalized.map {
                WorkspaceRecord(
                    projectId = it.projectId,
                    worktree = it.worktree,
                    name = it.name,
                    lastUsedAtMs = it.lastUsedAtMs
                )
            }
            settings.putString(key, json.encodeToString(records))
        }

        if (_installationIdForActiveServer.value == inst) {
            _workspaces.value = normalized
        }
    }

    override fun getActiveWorkspace(): Flow<Workspace?> {
        return _activeWorkspace.asStateFlow()
    }

    override fun getActiveWorkspaceSnapshot(): Workspace? {
        return _activeWorkspace.value
    }

    override suspend fun setActiveWorkspace(installationId: String, workspace: Workspace?) {
        val inst = installationId.trim()
        if (inst.isBlank()) return

        val key = "$KEY_ACTIVE_WORKSPACE_BY_INSTALLATION_PREFIX$inst"
        if (workspace == null) {
            settings.remove(key)
            if (_installationIdForActiveServer.value == inst) {
                _activeWorkspace.value = null
                _currentSessionId.value = settings.getStringOrNull(keyForCurrentSessionId(inst, null))
            }
            return
        }

        val projectId = workspace.projectId.trim()
        val worktree = workspace.worktree.trim()
        if (projectId.isBlank() || worktree.isBlank()) return

        val normalized = Workspace(
            projectId = projectId,
            worktree = worktree,
            name = workspace.name?.trim()?.takeIf { it.isNotBlank() },
            lastUsedAtMs = workspace.lastUsedAtMs
        )

        settings.putString(
            key,
            json.encodeToString(
                WorkspaceRecord(
                    projectId = normalized.projectId,
                    worktree = normalized.worktree,
                    name = normalized.name,
                    lastUsedAtMs = normalized.lastUsedAtMs
                )
            )
        )

        if (_installationIdForActiveServer.value == inst) {
            _activeWorkspace.value = normalized
            _currentSessionId.value = settings.getStringOrNull(keyForCurrentSessionId(inst, normalized.projectId))
        }
    }

    override fun getServerProfiles(): Flow<List<ServerProfile>> {
        return _serverProfiles.asStateFlow()
    }

    override fun getServerProfilesSnapshot(): List<ServerProfile> {
        return _serverProfiles.value
    }

    override suspend fun setServerProfiles(profiles: List<ServerProfile>) {
        val normalized = profiles.distinctBy { it.id }

        if (normalized.isEmpty()) {
            settings.remove(KEY_SERVER_PROFILES)
        } else {
            val records = normalized.map {
                ServerProfileRecord(
                    id = it.id,
                    name = it.name,
                    baseUrl = it.baseUrl,
                    createdAtMs = it.createdAtMs,
                    lastUsedAtMs = it.lastUsedAtMs
                )
            }
            settings.putString(KEY_SERVER_PROFILES, json.encodeToString(records))
        }

        _serverProfiles.value = normalized
    }

    override fun getActiveServerId(): Flow<String?> {
        return _activeServerId.asStateFlow()
    }

    override fun getActiveServerIdSnapshot(): String? {
        return _activeServerId.value
    }

    override suspend fun setActiveServerId(serverId: String?) {
        val normalized = serverId?.trim().takeIf { !it.isNullOrBlank() }
        if (normalized == null) {
            settings.remove(KEY_ACTIVE_SERVER_ID)
            _activeServerId.value = null
            updateDerivedWorkspaceState(activeServerId = null, installationId = null)
            return
        }

        settings.putString(KEY_ACTIVE_SERVER_ID, normalized)
        _activeServerId.value = normalized

        val installationId = readInstallationIdForServer(normalized)
        updateDerivedWorkspaceState(activeServerId = normalized, installationId = installationId)
    }

    override fun getAuthTokenForActiveServer(): Flow<String?> {
        return _authTokenForActiveServer.asStateFlow()
    }

    override fun getAuthTokenForActiveServerSnapshot(): String? {
        return _authTokenForActiveServer.value
    }

    override suspend fun resetConnectionState() {
        val preservedTheme = _themeMode.value
        val preservedAlwaysExpand = _alwaysExpandAssistantParts.value
        val preservedAssistantResponseVisibility = _assistantResponseVisibility.value

        settings.clear()

        // Restore UI preferences (do not wipe theme/UI prefs).
        settings.putString(KEY_THEME_MODE, preservedTheme.name)
        settings.putBoolean(KEY_ALWAYS_EXPAND_ASSISTANT_PARTS, preservedAlwaysExpand)
        settings.putString(KEY_ASSISTANT_RESPONSE_VISIBILITY, json.encodeToString(preservedAssistantResponseVisibility))

        _themeMode.value = preservedTheme
        _alwaysExpandAssistantParts.value = preservedAlwaysExpand
        _assistantResponseVisibility.value = preservedAssistantResponseVisibility

        // Reset reactive connection state immediately so the UI updates without waiting for a restart.
        _serverProfiles.value = emptyList()
        _activeServerId.value = null
        _selectedAgent.value = null
        _selectedModel.value = null
        _favoriteModels.value = emptyList()
        _thinkingVariantsByModel.value = emptyMap()

        updateDerivedWorkspaceState(activeServerId = null, installationId = null)
    }

    override suspend fun setAuthTokenForServer(serverId: String, token: String?) {
        val sid = serverId.trim()
        if (sid.isBlank()) return

        val normalized = token?.trim()?.takeIf { it.isNotBlank() }
        val key = "$KEY_AUTH_TOKEN_BY_SERVER_PREFIX$sid"
        if (normalized == null) {
            settings.remove(key)
        } else {
            settings.putString(key, normalized)
        }

        if (_activeServerId.value == sid) {
            _authTokenForActiveServer.value = normalized
        }
    }

    override fun getSelectedAgent(): Flow<String?> {
        return _selectedAgent.asStateFlow()
    }

    override suspend fun setSelectedAgent(agentName: String?) {
        // Persist to settings
        if (agentName == null) {
            settings.remove(KEY_SELECTED_AGENT)
        } else {
            settings.putString(KEY_SELECTED_AGENT, agentName)
        }
        // Update flow for reactive subscribers
        _selectedAgent.value = agentName
    }

    override fun getSelectedModel(): Flow<SelectedModel?> {
        return _selectedModel.asStateFlow()
    }

    override fun getSelectedModelSnapshot(): SelectedModel? {
        return _selectedModel.value
    }

    override suspend fun setSelectedModel(model: SelectedModel?) {
        // Persist to settings as "providerId:modelId" format
        if (model == null) {
            settings.remove(KEY_SELECTED_MODEL)
        } else {
            settings.putString(KEY_SELECTED_MODEL, "${model.providerId}:${model.modelId}")
        }
        // Update flow for reactive subscribers
        _selectedModel.value = model
    }

    override fun getFavoriteModels(): Flow<List<SelectedModel>> {
        return _favoriteModels.asStateFlow()
    }

    override fun getFavoriteModelsSnapshot(): List<SelectedModel> {
        return _favoriteModels.value
    }

    override suspend fun setFavoriteModels(models: List<SelectedModel>) {
        val normalized = normalizeFavoriteModels(models)

        if (normalized.isEmpty()) {
            settings.remove(KEY_FAVORITE_MODELS)
        } else {
            val encoded = json.encodeToString(normalized.map { "${it.providerId}:${it.modelId}" })
            settings.putString(KEY_FAVORITE_MODELS, encoded)
        }

        _favoriteModels.value = normalized
    }

    override fun getThemeMode(): Flow<ThemeMode> {
        return _themeMode.asStateFlow()
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    override fun getAlwaysExpandAssistantParts(): Flow<Boolean> {
        return _alwaysExpandAssistantParts.asStateFlow()
    }

    override suspend fun setAlwaysExpandAssistantParts(alwaysExpand: Boolean) {
        settings.putBoolean(KEY_ALWAYS_EXPAND_ASSISTANT_PARTS, alwaysExpand)
        _alwaysExpandAssistantParts.value = alwaysExpand
    }

    override fun getAssistantResponseVisibility(): Flow<AssistantResponseVisibilitySettings> {
        return _assistantResponseVisibility.asStateFlow()
    }

    override suspend fun setAssistantResponseVisibility(value: AssistantResponseVisibilitySettings) {
        settings.putString(KEY_ASSISTANT_RESPONSE_VISIBILITY, json.encodeToString(value))
        _assistantResponseVisibility.value = value
    }

    override fun getThinkingVariantsByModel(): Flow<Map<String, String>> {
        return _thinkingVariantsByModel.asStateFlow()
    }

    override suspend fun setThinkingVariantForModel(providerId: String, modelId: String, variant: String?) {
        val key = normalizeModelKey(providerId, modelId) ?: return
        val current = _thinkingVariantsByModel.value.toMutableMap()

        if (variant.isNullOrBlank()) {
            current.remove(key)
        } else {
            current[key] = variant.trim()
        }

        if (current.isEmpty()) {
            settings.remove(KEY_THINKING_VARIANTS_BY_MODEL)
        } else {
            settings.putString(KEY_THINKING_VARIANTS_BY_MODEL, json.encodeToString(current))
        }
        _thinkingVariantsByModel.value = current.toMap()
    }

    private fun parseSelectedModel(stored: String?): SelectedModel? {
        if (stored == null) return null
        val parts = stored.split(":", limit = 2)
        return if (parts.size == 2) {
            SelectedModel(providerId = parts[0], modelId = parts[1])
        } else {
            null
        }
    }

    private fun parseFavoriteModels(stored: String?): List<SelectedModel> {
        if (stored.isNullOrBlank()) return emptyList()

        val keys: List<String> = try {
            json.decodeFromString(stored)
        } catch (_: Exception) {
            return emptyList()
        }

        val models = keys.mapNotNull { key ->
            val parts = key.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val providerId = parts[0].trim()
            val modelId = parts[1].trim()
            if (providerId.isBlank() || modelId.isBlank()) return@mapNotNull null
            SelectedModel(providerId = providerId, modelId = modelId)
        }

        return normalizeFavoriteModels(models)
    }

    private fun normalizeFavoriteModels(models: List<SelectedModel>): List<SelectedModel> {
        val unique = models.distinctBy { "${it.providerId}:${it.modelId}" }
        return unique.sortedWith(compareBy<SelectedModel> { it.providerId.lowercase() }.thenBy { it.modelId.lowercase() })
    }

    private fun parseThemeMode(stored: String?): ThemeMode {
        return stored?.let {
            try {
                ThemeMode.valueOf(it)
            } catch (e: IllegalArgumentException) {
                AppSettings.DEFAULT_THEME_MODE
            }
        } ?: AppSettings.DEFAULT_THEME_MODE
    }

    private fun parseAssistantResponseVisibility(stored: String?): AssistantResponseVisibilitySettings {
        if (stored.isNullOrBlank()) return AssistantResponseVisibilitySettings()
        return try {
            json.decodeFromString(stored)
        } catch (_: Exception) {
            AssistantResponseVisibilitySettings()
        }
    }

    private fun parseThinkingVariantsByModel(stored: String?): Map<String, String> {
        if (stored.isNullOrBlank()) return emptyMap()
        val decoded: Map<String, String> = try {
            json.decodeFromString(stored)
        } catch (_: Exception) {
            return emptyMap()
        }

        val normalized = decoded.entries
            .mapNotNull { (rawKey, rawValue) ->
                val keyParts = rawKey.split(":", limit = 2)
                if (keyParts.size != 2) return@mapNotNull null
                val key = normalizeModelKey(keyParts[0], keyParts[1]) ?: return@mapNotNull null
                val value = rawValue.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to value
            }
            .toMap()

        return normalized
    }

    @kotlinx.serialization.Serializable
    private data class ServerProfileRecord(
        val id: String,
        val name: String,
        val baseUrl: String,
        val createdAtMs: Long,
        val lastUsedAtMs: Long? = null
    )

    private fun parseServerProfiles(stored: String?): List<ServerProfile> {
        if (stored.isNullOrBlank()) return emptyList()

        val decoded: List<ServerProfileRecord> = try {
            json.decodeFromString(stored)
        } catch (_: Exception) {
            return emptyList()
        }

        return decoded
            .asSequence()
            .mapNotNull { record ->
                val id = record.id.trim()
                val name = record.name.trim()
                val baseUrl = record.baseUrl.trim()
                if (id.isBlank() || name.isBlank() || baseUrl.isBlank()) return@mapNotNull null
                ServerProfile(
                    id = id,
                    name = name,
                    baseUrl = baseUrl,
                    createdAtMs = record.createdAtMs,
                    lastUsedAtMs = record.lastUsedAtMs
                )
            }
            .distinctBy { it.id }
            .toList()
    }

    @kotlinx.serialization.Serializable
    private data class WorkspaceRecord(
        val projectId: String,
        val worktree: String,
        val name: String? = null,
        val lastUsedAtMs: Long? = null
    )

    private fun parseWorkspaces(stored: String?): List<Workspace> {
        if (stored.isNullOrBlank()) return emptyList()

        val decoded: List<WorkspaceRecord> = try {
            json.decodeFromString(stored)
        } catch (_: Exception) {
            return emptyList()
        }

        return decoded
            .asSequence()
            .mapNotNull { record ->
                val projectId = record.projectId.trim()
                val worktree = record.worktree.trim()
                if (projectId.isBlank() || worktree.isBlank()) return@mapNotNull null
                Workspace(
                    projectId = projectId,
                    worktree = worktree,
                    name = record.name?.trim()?.takeIf { it.isNotBlank() },
                    lastUsedAtMs = record.lastUsedAtMs
                )
            }
            .distinctBy { it.projectId }
            .toList()
    }

    private fun parseWorkspace(stored: String?): Workspace? {
        if (stored.isNullOrBlank()) return null
        val decoded: WorkspaceRecord = try {
            json.decodeFromString(stored)
        } catch (_: Exception) {
            return null
        }

        val projectId = decoded.projectId.trim()
        val worktree = decoded.worktree.trim()
        if (projectId.isBlank() || worktree.isBlank()) return null
        return Workspace(
            projectId = projectId,
            worktree = worktree,
            name = decoded.name?.trim()?.takeIf { it.isNotBlank() },
            lastUsedAtMs = decoded.lastUsedAtMs
        )
    }

    private fun readInstallationIdForServer(serverId: String?): String? {
        val sid = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return settings.getStringOrNull("$KEY_INSTALLATION_ID_BY_SERVER_PREFIX$sid")
    }

    private fun readAuthTokenForServer(serverId: String?): String? {
        val sid = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return settings.getStringOrNull("$KEY_AUTH_TOKEN_BY_SERVER_PREFIX$sid")
    }

    private fun updateDerivedWorkspaceState(activeServerId: String?, installationId: String?) {
        val inst = installationId?.trim()?.takeIf { it.isNotBlank() } ?: readInstallationIdForServer(activeServerId)
        _installationIdForActiveServer.value = inst
        _authTokenForActiveServer.value = readAuthTokenForServer(activeServerId)

        _workspaces.value = parseWorkspaces(settings.getStringOrNull(keyForWorkspaces(inst)))
        _activeWorkspace.value = parseWorkspace(settings.getStringOrNull(keyForActiveWorkspace(inst)))

        _currentSessionId.value = settings.getStringOrNull(
            keyForCurrentSessionId(
                installationId = inst,
                projectId = _activeWorkspace.value?.projectId
            )
        )
    }

    private fun keyForWorkspaces(installationId: String?): String {
        val normalized = installationId?.trim()?.takeIf { it.isNotBlank() } ?: return "${KEY_WORKSPACES_BY_INSTALLATION_PREFIX}__none__"
        return "$KEY_WORKSPACES_BY_INSTALLATION_PREFIX$normalized"
    }

    private fun keyForActiveWorkspace(installationId: String?): String {
        val normalized = installationId?.trim()?.takeIf { it.isNotBlank() } ?: return "${KEY_ACTIVE_WORKSPACE_BY_INSTALLATION_PREFIX}__none__"
        return "$KEY_ACTIVE_WORKSPACE_BY_INSTALLATION_PREFIX$normalized"
    }

    private fun keyForCurrentSessionId(installationId: String?, projectId: String?): String {
        val inst = installationId?.trim()?.takeIf { it.isNotBlank() } ?: return KEY_LEGACY_CURRENT_SESSION_ID
        val project = projectId?.trim()?.takeIf { it.isNotBlank() }
        return if (project != null) {
            "$KEY_CURRENT_SESSION_ID_BY_INSTALLATION_PROJECT_PREFIX$inst:$project"
        } else {
            "$KEY_CURRENT_SESSION_ID_BY_INSTALLATION_PREFIX$inst"
        }
    }

    private fun normalizeModelKey(providerId: String, modelId: String): String? {
        val p = providerId.trim()
        val m = modelId.trim()
        if (p.isBlank() || m.isBlank()) return null
        return "$p:$m"
    }

    companion object {
        private const val KEY_LEGACY_CURRENT_SESSION_ID = "current_session_id"
        private const val KEY_CURRENT_SESSION_ID_BY_INSTALLATION_PREFIX = "current_session_id_by_installation:"
        private const val KEY_CURRENT_SESSION_ID_BY_INSTALLATION_PROJECT_PREFIX = "current_session_id_by_installation_project:"
        private const val KEY_INSTALLATION_ID_BY_SERVER_PREFIX = "installation_id_by_server:"
        private const val KEY_AUTH_TOKEN_BY_SERVER_PREFIX = "auth_token_by_server:"
        private const val KEY_WORKSPACES_BY_INSTALLATION_PREFIX = "workspaces_by_installation:"
        private const val KEY_ACTIVE_WORKSPACE_BY_INSTALLATION_PREFIX = "active_workspace_by_installation:"
        private const val KEY_SERVER_PROFILES = "server_profiles"
        private const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        private const val KEY_SELECTED_AGENT = "selected_agent"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_FAVORITE_MODELS = "favorite_models"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ALWAYS_EXPAND_ASSISTANT_PARTS = "always_expand_assistant_parts"
        private const val KEY_ASSISTANT_RESPONSE_VISIBILITY = "assistant_response_visibility"
        private const val KEY_THINKING_VARIANTS_BY_MODEL = "thinking_variants_by_model"
    }
}
