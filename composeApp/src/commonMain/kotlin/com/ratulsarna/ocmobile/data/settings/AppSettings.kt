package com.ratulsarna.ocmobile.data.settings

import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilitySettings
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.model.ServerProfile
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Interface for app settings/preferences.
 */
interface AppSettings {
    /**
     * Get the locally stored active session ID as a flow.
     *
     * This is used to switch the chat session when the user activates a session from History.
     */
    fun getCurrentSessionId(): Flow<String?>

    /**
     * Get the locally stored active session ID (non-reactive snapshot).
     */
    fun getCurrentSessionIdSnapshot(): String?

    /**
     * Persist the locally stored active session ID.
     *
     * @param sessionId The session ID to store, or null to clear
     */
    suspend fun setCurrentSessionId(sessionId: String?)

    /**
     * Get the derived "installation id" for the active server.
     *
     * The installation id is a stable identifier for an OpenCode installation on a machine
     * (derived from server-reported state/config paths). This lets the app share workspace/session
     * memory across multiple server profiles that point to the same machine/storage.
     */
    fun getInstallationIdForActiveServer(): Flow<String?>

    /**
     * Snapshot of derived installation id for the active server.
     */
    fun getInstallationIdForActiveServerSnapshot(): String?

    /**
     * Persist the installation id mapping for a specific server profile.
     */
    suspend fun setInstallationIdForServer(serverId: String, installationId: String)

    /**
     * Get saved workspaces for the active installation.
     */
    fun getWorkspaces(): Flow<List<Workspace>>

    /**
     * Snapshot of saved workspaces for the active installation.
     */
    fun getWorkspacesSnapshot(): List<Workspace>

    /**
     * Persist workspaces for a given installation id.
     */
    suspend fun setWorkspacesForInstallation(installationId: String, workspaces: List<Workspace>)

    /**
     * Get the selected active workspace for the active installation.
     */
    fun getActiveWorkspace(): Flow<Workspace?>

    /**
     * Snapshot of the active workspace for the active installation.
     */
    fun getActiveWorkspaceSnapshot(): Workspace?

    /**
     * Persist the active workspace for an installation id (or clear it).
     */
    suspend fun setActiveWorkspace(installationId: String, workspace: Workspace?)

    /**
     * Get saved OpenCode server profiles.
     */
    fun getServerProfiles(): Flow<List<ServerProfile>>

    /**
     * Snapshot of saved server profiles.
     */
    fun getServerProfilesSnapshot(): List<ServerProfile>

    /**
     * Persist server profiles.
     */
    suspend fun setServerProfiles(profiles: List<ServerProfile>)

    /**
     * Get active server profile id (the rest of the app is scoped to this server).
     */
    fun getActiveServerId(): Flow<String?>

    /**
     * Snapshot of active server profile id.
     */
    fun getActiveServerIdSnapshot(): String?

    /**
     * Persist active server profile id.
     */
    suspend fun setActiveServerId(serverId: String?)

    /**
     * Get the auth token for the active server profile.
     *
     * This token is used for `Authorization: Bearer <token>` when talking to a token-protected
     * gateway (for example the `oc-pocket` companion).
     */
    fun getAuthTokenForActiveServer(): Flow<String?>

    /**
     * Snapshot of auth token for the active server profile.
     */
    fun getAuthTokenForActiveServerSnapshot(): String?

    /**
     * Persist the auth token for a specific server profile id.
     *
     * Passing null/blank clears the token.
     */
    suspend fun setAuthTokenForServer(serverId: String, token: String?)

    /**
     * Wipe local connection-related state so the app behaves like a fresh install with respect to
     * connection.
     *
     * This clears server profiles, auth tokens, derived installation/workspace/session state, and
     * model/agent selections. It must NOT clear theme/UI preferences.
     */
    suspend fun resetConnectionState()

    /**
     * Get the selected agent name as a flow.
     * Returns the default agent if no selection has been made.
     */
    fun getSelectedAgent(): Flow<String?>

    /**
     * Set the selected agent.
     * @param agentName The agent name, or null to clear selection
     */
    suspend fun setSelectedAgent(agentName: String?)

    /**
     * Get the selected model as a flow.
     * Returns null if no selection has been made.
     */
    fun getSelectedModel(): Flow<SelectedModel?>

    /**
     * Get the current selected model value (non-reactive snapshot).
     * Returns null if no selection has been made.
     */
    fun getSelectedModelSnapshot(): SelectedModel?

    /**
     * Set the selected model.
     * @param model The model to select, or null to clear selection
     */
    suspend fun setSelectedModel(model: SelectedModel?)

    /**
     * Get the user's favorited models as a flow.
     * Favorites affect model selection UI ordering only (they do not select a model).
     */
    fun getFavoriteModels(): Flow<List<SelectedModel>>

    /**
     * Get the current favorite models value (non-reactive snapshot).
     */
    fun getFavoriteModelsSnapshot(): List<SelectedModel>

    /**
     * Persist the user's favorited models.
     */
    suspend fun setFavoriteModels(models: List<SelectedModel>)

    /**
     * Get the theme mode as a flow.
     * Returns SYSTEM by default.
     */
    fun getThemeMode(): Flow<ThemeMode>

    /**
     * Set the theme mode.
     * @param mode The theme mode to use
     */
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * Control whether assistant detail parts (reasoning/tools) default to expanded.
     */
    fun getAlwaysExpandAssistantParts(): Flow<Boolean>

    /**
     * Set whether assistant detail parts (reasoning/tools) default to expanded.
     */
    suspend fun setAlwaysExpandAssistantParts(alwaysExpand: Boolean)

    /**
     * Controls which non-text assistant message parts are visible in chat.
     * Assistant plain text is always shown; attachments (FilePart) remain visible.
     */
    fun getAssistantResponseVisibility(): Flow<AssistantResponseVisibilitySettings>

    /**
     * Persist assistant response visibility preference.
     */
    suspend fun setAssistantResponseVisibility(value: AssistantResponseVisibilitySettings)

    /**
     * Per-model thinking/variant override keyed by "providerId:modelId".
     *
     * When present, Chat will send this as the prompt `variant` so the server enables the
     * corresponding thinking level (e.g., "high", "max"). When absent, we omit `variant`
     * and rely on the server/model default.
     */
    fun getThinkingVariantsByModel(): Flow<Map<String, String>>

    /**
     * Persist a per-model thinking/variant override.
     * @param providerId model provider (e.g. "anthropic")
     * @param modelId model id (e.g. "claude-opus-4-5")
     * @param variant override value, or null to clear (Auto)
     */
    suspend fun setThinkingVariantForModel(providerId: String, modelId: String, variant: String?)

    companion object {
        /** Default theme mode */
        val DEFAULT_THEME_MODE = ThemeMode.SYSTEM
        /** Default for expanding assistant detail parts */
        const val DEFAULT_ALWAYS_EXPAND_ASSISTANT_PARTS = false
    }
}
