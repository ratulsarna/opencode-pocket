package com.ratulsarna.ocmobile.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.domain.model.AssistantMessage
import com.ratulsarna.ocmobile.domain.model.Attachment
import com.ratulsarna.ocmobile.domain.model.AttachmentError
import com.ratulsarna.ocmobile.domain.model.AttachmentLimits
import com.ratulsarna.ocmobile.domain.model.FilePart
import com.ratulsarna.ocmobile.domain.model.Message
import com.ratulsarna.ocmobile.domain.model.MessagePartUpdatedEvent
import com.ratulsarna.ocmobile.domain.model.MessageRemovedEvent
import com.ratulsarna.ocmobile.domain.model.MessageUpdatedEvent
import com.ratulsarna.ocmobile.domain.model.TextPart
import com.ratulsarna.ocmobile.domain.model.UserMessage
import com.ratulsarna.ocmobile.domain.model.VaultEntry
import com.ratulsarna.ocmobile.domain.model.VaultEntryType
import com.ratulsarna.ocmobile.domain.model.CommandInfo
import kotlin.time.Clock
import kotlinx.datetime.Instant
import com.ratulsarna.ocmobile.domain.error.MessageAbortedError
import com.ratulsarna.ocmobile.domain.error.UnauthorizedError
import com.ratulsarna.ocmobile.domain.model.SessionUpdatedEvent
import com.ratulsarna.ocmobile.domain.model.SessionStatus
import com.ratulsarna.ocmobile.domain.model.SessionStatusEvent
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.model.PermissionAskedEvent
import com.ratulsarna.ocmobile.domain.model.PermissionRepliedEvent
import com.ratulsarna.ocmobile.domain.model.PermissionReply
import com.ratulsarna.ocmobile.domain.model.PermissionRequest
import com.ratulsarna.ocmobile.domain.repository.AgentRepository
import com.ratulsarna.ocmobile.domain.repository.ConnectionState
import com.ratulsarna.ocmobile.domain.repository.ContextUsageRepository
import com.ratulsarna.ocmobile.domain.repository.EventStream
import com.ratulsarna.ocmobile.domain.repository.MessageRepository
import com.ratulsarna.ocmobile.domain.repository.ModelRepository
import com.ratulsarna.ocmobile.domain.repository.ModelSpec
import com.ratulsarna.ocmobile.domain.repository.PermissionRepository
import com.ratulsarna.ocmobile.domain.repository.ServerRepository
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.domain.repository.WorkspaceRepository
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.platform.ClipboardImageReader
import com.ratulsarna.ocmobile.platform.PlatformInfo
import com.ratulsarna.ocmobile.util.OcMobileLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ChatVM"
private const val FOREGROUND_REFRESH_MIN_BACKGROUND_MS = 60_000L
private const val PROVIDERS_CACHE_TTL_MS = 60_000L
private const val COMMANDS_REFRESH_THROTTLE_MS = 10_000L
private const val SEND_FAILURE_GRACE_MS = 20_000L

private data class PermissionEntry(
    val request: PermissionRequest,
    val addedAtMs: Long
)

class ChatViewModel(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val eventStream: EventStream,
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
    private val clipboardImageReader: ClipboardImageReader,
    private val contextUsageRepository: ContextUsageRepository,
    private val appSettings: AppSettings,
    private val permissionRepository: PermissionRepository,
    private val serverRepository: ServerRepository,
    private val workspaceRepository: WorkspaceRepository,
    /**
     * Clipboard polling interval in milliseconds.
     *
     * Set to `0` (or negative) to disable polling entirely (useful for unit tests
     * running with `kotlinx-coroutines-test`, where an infinite `delay()` loop can
     * prevent the test scheduler from ever becoming idle).
     */
    private val clipboardPollingIntervalMs: Long = PlatformInfo.defaultClipboardPollingIntervalMs
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * One-time bootstrap gate for workspace-scoped network calls.
     *
     * Many endpoints (and SSE) are scoped via the `x-opencode-directory` header. On a fresh pairing, the app may not
     * have an active workspace yet, so making requests immediately can produce transient 401s that succeed after the
     * workspace is initialized.
     */
    private val workspaceBootstrap = CompletableDeferred<Unit>()

    private var didTriggerAuthLogout: Boolean = false

    private suspend fun logoutDueToUnauthorized(message: String?) {
        if (didTriggerAuthLogout) return
        didTriggerAuthLogout = true

        val activeServerId = serverRepository.getActiveServerIdSnapshot()?.trim().takeIf { !it.isNullOrBlank() }
        if (activeServerId != null) {
            runCatching { appSettings.setAuthTokenForServer(activeServerId, token = null) }
        }

        // Keep UI stable; SwiftUI root will route to Connect when the token is cleared.
        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = ChatError.Unauthorized(message)) }
    }

    private fun applyRevertPointer(state: ChatUiState, revertMessageId: String?): ChatUiState {
        val filtered = filterMessagesForRevert(state.messages, revertMessageId)

        val lastGoodId = filtered.lastOrNull { msg ->
            msg is AssistantMessage && msg.error == null
        }?.id

        val latestAssistant = filtered.filterIsInstance<AssistantMessage>().lastOrNull()
        val latestAssistantError = latestAssistant?.error
        val isAbortError = latestAssistantError is MessageAbortedError

        val sessionCorruptedFromMessage =
            latestAssistantError != null && !isAbortError && lastGoodId != null

        val nextError = when {
            isAbortError && state.error is ChatError.SessionCorrupted -> null
            latestAssistantError == null && state.error is ChatError.SessionCorrupted -> null
            sessionCorruptedFromMessage && state.error == null -> ChatError.SessionCorrupted(
                "Assistant message contains error"
            )
            else -> state.error
        }

        val nextStreamingId = state.streamingMessageId?.takeIf { id ->
            filtered.any { it.id == id }
        }

        val selectionIsVisible = state.selectedMessageForAction?.let { selected ->
            filtered.any { it.id == selected.id }
        } ?: true

        return state.copy(
            revertMessageId = revertMessageId,
            messages = filtered,
            lastGoodMessageId = lastGoodId,
            error = nextError,
            streamingMessageId = nextStreamingId,
            selectedMessageForAction = if (selectionIsVisible) state.selectedMessageForAction else null,
            showRevertConfirmation = if (selectionIsVisible) state.showRevertConfirmation else false
        )
    }

    /** Current selected agent (from persistent storage) */
    private var currentAgent: String? = null

    /** Current selected model (from persistent storage) */
    private var currentModel: SelectedModel? = null

    /** Counter for generating unique optimistic message IDs */
    private var pendingMessageCounter = 0L

    private var pendingSendFailureJob: Job? = null

    private var cachedProviders: List<com.ratulsarna.ocmobile.domain.model.Provider>? = null
    private var cachedProvidersAtMs: Long? = null

    private var thinkingVariantsByModel: Map<String, String> = emptyMap()

    // SSE is split across `message.updated` (metadata/role) and `message.part.updated` (parts).
    // Buffer parts for unknown message IDs until we learn role, to avoid rendering user messages
    // as assistant placeholders during streaming.
    private val messageRoleById = mutableMapOf<String, String>() // messageId -> role ("user"/"assistant")
    private val bufferedPartsByMessageId = mutableMapOf<String, MutableList<com.ratulsarna.ocmobile.domain.model.MessagePart>>()
    private val userMessagesAwaitingServerParts = mutableSetOf<String>()

    private fun clearStreamingBuffers() {
        messageRoleById.clear()
        bufferedPartsByMessageId.clear()
        userMessagesAwaitingServerParts.clear()
    }

    private fun isTransientSendNetworkError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message?.lowercase().orEmpty()
            if (
                message.contains("nsurlerrordomain") ||
                    message.contains("kcferrordomaincfnetwork") ||
                    message.contains("code=-1005") ||
                    message.contains("the network connection was lost")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun modelKey(providerId: String, modelId: String): String = "$providerId:$modelId"

    private suspend fun refreshProvidersCacheIfNeeded(force: Boolean) {
        workspaceBootstrap.await()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val cachedAt = cachedProvidersAtMs
        val isFresh = cachedAt != null && (nowMs - cachedAt) < PROVIDERS_CACHE_TTL_MS
        if (!force && cachedProviders != null && isFresh) return

        modelRepository.getConnectedProviders()
            .onSuccess { providers ->
                cachedProviders = providers
                cachedProvidersAtMs = nowMs
            }
            .onFailure { e ->
                OcMobileLog.w(TAG, "Failed to refresh providers for thinking variants: ${e.message}")
            }
    }

    private fun updateThinkingUiStateForCurrentModel() {
        val selected = currentModel
        if (selected == null) {
            _uiState.update { it.copy(thinkingVariants = emptyList(), thinkingVariant = null) }
            return
        }

        val providers = cachedProviders.orEmpty()
        val variants = providers
            .firstOrNull { it.id == selected.providerId }
            ?.models
            ?.firstOrNull { it.id == selected.modelId }
            ?.variants
            .orEmpty()

        val key = modelKey(selected.providerId, selected.modelId)
        val stored = thinkingVariantsByModel[key]
        val effective = stored?.takeIf { variants.contains(it) }

        _uiState.update { it.copy(thinkingVariants = variants, thinkingVariant = effective) }
    }

    private fun refreshThinkingVariantsForCurrentModel(forceProvidersRefresh: Boolean) {
        viewModelScope.launch {
            refreshProvidersCacheIfNeeded(force = forceProvidersRefresh)
            updateThinkingUiStateForCurrentModel()
        }
    }

    fun setThinkingVariant(variant: String?) {
        val selected = currentModel ?: return
        val trimmed = variant?.trim()?.takeIf { it.isNotBlank() }

        // Validate against current server-reported variants to avoid crashing server-side option merge.
        val allowed = _uiState.value.thinkingVariants
        val effective = trimmed?.takeIf { allowed.contains(it) }
        val key = modelKey(selected.providerId, selected.modelId)

        // Keep the send-time source of truth in sync with the immediate UI update.
        // This prevents a "select variant → quickly send" race where we might send the previous value.
        thinkingVariantsByModel = if (effective == null) {
            thinkingVariantsByModel - key
        } else {
            thinkingVariantsByModel + (key to effective)
        }

        viewModelScope.launch {
            appSettings.setThinkingVariantForModel(
                providerId = selected.providerId,
                modelId = selected.modelId,
                variant = effective
            )
        }

        _uiState.update { it.copy(thinkingVariant = effective) }
    }

    private suspend fun resolvePromptVariant(model: ModelSpec?): String? {
        if (model == null) return null

        val key = modelKey(model.providerId, model.modelId)
        val desired = thinkingVariantsByModel[key] ?: return null

        // Validate against server-reported variants to avoid crashing server-side mergeDeep(undefined).
        refreshProvidersCacheIfNeeded(force = false)
        val variants = cachedProviders
            .orEmpty()
            .firstOrNull { it.id == model.providerId }
            ?.models
            ?.firstOrNull { it.id == model.modelId }
            ?.variants
            .orEmpty()

        return desired.takeIf { variants.contains(it) }
    }

    // Prevent overlapping message reloads (which can cause network congestion/timeouts on device).
    private var loadMessagesJob: kotlinx.coroutines.Job? = null
    private var loadMessagesInFlightSessionId: String? = null

    // Debounced mention search job
    private var mentionSearchJob: Job? = null

    // Debounced slash command filtering job
    private var slashCommandSearchJob: Job? = null

    private var lastBackgroundedAtMs: Long? = null

    // Pending permission requests (stable iteration order for "next" prompt)
    private val permissionRequestsById = LinkedHashMap<String, PermissionEntry>()

    // Slash command cache (fetched from server via GET /command)
    private var knownCommands: List<CommandInfo> = emptyList()
    private var knownCommandNames: Set<String> = emptySet()
    private var commandsLoaded: Boolean = false
    private var lastCommandsRefreshAttemptAtMs: Long? = null
    private var loadCommandsJob: Job? = null

    init {
        initializeWorkspaceScopedState()
        observeCurrentSessionId() // Local persistence + History "Make Active"
        subscribeToEvents()
        observeSelectedAgent()
        observeSelectedModel()
        observeThinkingVariantsByModel()
        // loadCommands is part of initializeWorkspaceScopedState() so it is scoped to the active workspace.
        observeAlwaysExpandAssistantParts()
        observeAssistantResponseVisibility()
        observeClipboard()
    }

    private fun initializeWorkspaceScopedState() {
        viewModelScope.launch {
            try {
                serverRepository.ensureInitialized()
                    .onFailure { error ->
                        OcMobileLog.w(TAG, "Server init failed (continuing with defaults): ${error.message}")
                    }

                workspaceRepository.ensureInitialized()
                    .onFailure { error ->
                        OcMobileLog.w(TAG, "Workspace init failed (continuing with server defaults): ${error.message}")
                    }
            } finally {
                if (!workspaceBootstrap.isCompleted) {
                    workspaceBootstrap.complete(Unit)
                }
            }

            loadCurrentSession() // Fallback via REST API
            loadCommands(force = true)
            refreshThinkingVariantsForCurrentModel(forceProvidersRefresh = true)
        }
    }

    private fun shouldRefreshCommands(nowMs: Long): Boolean {
        val lastAttempt = lastCommandsRefreshAttemptAtMs
        if (lastAttempt != null && nowMs - lastAttempt < COMMANDS_REFRESH_THROTTLE_MS) return false
        return true
    }

    private suspend fun fetchCommands(force: Boolean): Boolean {
        workspaceBootstrap.await()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        if (!force && commandsLoaded) return true
        if (!force && !shouldRefreshCommands(nowMs)) return commandsLoaded

        lastCommandsRefreshAttemptAtMs = nowMs
        val result = messageRepository.listCommands()
        result
            .onSuccess { commands ->
                knownCommands = commands
                knownCommandNames = commands.asSequence().map { it.name }.toSet()
                commandsLoaded = true

                val state = _uiState.value.slashCommandState
                if (state is SlashCommandState.Active) {
                    val suggestions = filterSlashCommandSuggestions(state.query)
                    val selectedIndex = if (suggestions.isEmpty()) 0 else state.selectedIndex.coerceIn(0, suggestions.lastIndex)
                    _uiState.update {
                        it.copy(
                            slashCommandState = state.copy(
                                isLoading = false,
                                suggestions = suggestions,
                                selectedIndex = selectedIndex,
                                error = null
                            )
                        )
                    }
                }
            }
            .onFailure { e ->
                OcMobileLog.w(TAG, "Failed to load commands: ${e.message}")
                val state = _uiState.value.slashCommandState
                if (state is SlashCommandState.Active) {
                    val suggestions = if (commandsLoaded) {
                        filterSlashCommandSuggestions(state.query)
                    } else {
                        emptyList()
                    }
                    val selectedIndex = if (suggestions.isEmpty()) {
                        0
                    } else {
                        state.selectedIndex.coerceIn(0, suggestions.lastIndex)
                    }
                    _uiState.update {
                        it.copy(
                            slashCommandState = state.copy(
                                isLoading = false,
                                suggestions = suggestions,
                                selectedIndex = selectedIndex,
                                error = if (suggestions.isEmpty()) (e.message ?: "Failed to load commands") else null
                            )
                        )
                    }
                }
            }
        return result.isSuccess
    }

    private fun loadCommands(force: Boolean) {
        if (loadCommandsJob?.isActive == true) return
        loadCommandsJob = viewModelScope.launch {
            fetchCommands(force = force)
        }
    }

    fun refreshCommands() {
        loadCommands(force = true)
    }

    private fun refreshPendingPermissions() {
        viewModelScope.launch {
            val refreshStartedAtMs = Clock.System.now().toEpochMilliseconds()
            permissionRepository.getPendingRequests()
                .onSuccess { requests ->
                    val serverIds = requests.asSequence().map { it.requestId }.toSet()

                    // Merge server state without dropping permission requests that may have arrived via SSE
                    // after this refresh started.
                    requests.forEach { request ->
                        val existing = permissionRequestsById[request.requestId]
                        permissionRequestsById[request.requestId] = PermissionEntry(
                            request = request,
                            addedAtMs = existing?.addedAtMs ?: refreshStartedAtMs
                        )
                    }

                    // Remove stale entries that predate this refresh and are no longer returned by the server.
                    val iterator = permissionRequestsById.entries.iterator()
                    while (iterator.hasNext()) {
                        val (id, entry) = iterator.next()
                        if (entry.addedAtMs < refreshStartedAtMs && id !in serverIds) {
                            iterator.remove()
                        }
                    }
                    updatePendingPermissionInState()
                }
                .onFailure { e ->
                    OcMobileLog.w(TAG, "Failed to load pending permissions: ${e.message}")
                    // Ensure any SSE-delivered permission requests are surfaced even if refresh fails.
                    updatePendingPermissionInState()
                }
        }
    }

    private fun updatePendingPermissionInState() {
        val currentSessionId = _uiState.value.currentSessionId
        val next = currentSessionId?.let { sid ->
            permissionRequestsById.values.firstOrNull { it.request.sessionId == sid }?.request
        }
        _uiState.update { state ->
            val currentId = state.pendingPermission?.requestId
            val nextId = next?.requestId
            if (currentId == nextId) state else state.copy(pendingPermission = next)
        }
    }

    private fun observeSelectedAgent() {
        viewModelScope.launch {
            agentRepository.getSelectedAgent().collect { agentName ->
                currentAgent = agentName
            }
        }
    }

    private fun observeSelectedModel() {
        viewModelScope.launch {
            modelRepository.getSelectedModel().collect { selectedModel ->
                currentModel = selectedModel
                refreshThinkingVariantsForCurrentModel(forceProvidersRefresh = false)
            }
        }
    }

    private fun observeThinkingVariantsByModel() {
        viewModelScope.launch {
            appSettings.getThinkingVariantsByModel().collect { map ->
                thinkingVariantsByModel = map
                refreshThinkingVariantsForCurrentModel(forceProvidersRefresh = false)
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
            appSettings.getAssistantResponseVisibility().collect { settings ->
                _uiState.update {
                    it.copy(
                        assistantResponseVisibilityPreset = settings.preset,
                        assistantResponsePartVisibility = settings.effective()
                    )
                }
            }
        }
    }

    /**
     * Periodically check clipboard for images.
     */
    private fun observeClipboard() {
        if (clipboardPollingIntervalMs <= 0) return
        viewModelScope.launch {
            while (isActive) {
                try {
                    val hasImage = clipboardImageReader.hasImage()
                    _uiState.update { it.copy(hasClipboardImage = hasImage) }
                } catch (e: Exception) {
                    OcMobileLog.e(TAG, "Error checking clipboard: ${e.message}")
                }
                delay(clipboardPollingIntervalMs)  // Check periodically
            }
        }
    }

    /**
     * Add an attachment to the pending list.
     */
    fun addAttachment(attachment: Attachment) {
        val currentCount = _uiState.value.pendingAttachments.size

        // Validate using AttachmentLimits
        val error = AttachmentLimits.validate(attachment, currentCount)
        if (error != null) {
            _uiState.update { it.copy(attachmentError = error) }
            return
        }

        _uiState.update {
            it.copy(pendingAttachments = it.pendingAttachments + attachment)
        }
    }

    /**
     * Remove an attachment from the pending list.
     */
    fun removeAttachment(attachment: Attachment) {
        _uiState.update {
            it.copy(pendingAttachments = it.pendingAttachments.filter { a -> a.id != attachment.id })
        }
    }

    /**
     * Add image from clipboard.
     */
    fun addFromClipboard() {
        viewModelScope.launch {
            try {
                val attachment = clipboardImageReader.readImage()
                if (attachment != null) {
                    addAttachment(attachment)
                } else {
                    // On iOS we don't pre-detect clipboard images; provide feedback on tap.
                    _uiState.update { it.copy(attachmentError = AttachmentError.NoClipboardImage) }
                }
            } catch (e: Exception) {
                OcMobileLog.e(TAG, "Error reading clipboard image: ${e.message}")
            }
        }
    }

    /**
     * Dismiss attachment error.
     */
    fun dismissAttachmentError() {
        _uiState.update { it.copy(attachmentError = null) }
    }

    /**
     * Set an attachment error to display to the user.
     * Used by file pickers to report errors.
     */
    fun setAttachmentError(error: AttachmentError) {
        _uiState.update { it.copy(attachmentError = error) }
    }

    /**
     * Search for vault files on the server.
     * Used by VaultFilePicker for fuzzy file search.
     */
    suspend fun findVaultEntries(query: String): Result<List<VaultEntry>> {
        return messageRepository.findVaultEntries(query)
    }

    /**
     * SwiftUI migration helper: like [findVaultEntries], but throws on failure so Swift can
     * use SKIE `async/await` ergonomically (`try await ...`).
     */
    suspend fun findVaultEntriesOrThrow(query: String): List<VaultEntry> {
        return findVaultEntries(query).getOrElse { throw it }
    }


    // ==================== Input Text State Functions ====================

    /**
     * Set the input text programmatically.
     * Used by Share Extension to pre-fill shared text.
     * Sets cursor to end of text.
     */
    fun setInputText(text: String) {
        _uiState.update {
            it.copy(
                inputText = text,
                inputCursor = text.length,
                mentionState = MentionState.Inactive,
                slashCommandState = SlashCommandState.Inactive
            )
        }
    }

    /**
     * Handle input text changes from ChatInput.
     * Called on every keystroke.
     */
    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * Send the current message using inputText and pendingAttachments from UI state.
     * Clears both atomically after initiating send.
     */
    fun sendCurrentMessage() {
        val state = _uiState.value
        val text = state.inputText.trimEnd()
        val attachments = state.pendingAttachments

        if (text.isBlank() && attachments.isEmpty()) return

        // Clear input text, cursor, and attachments atomically BEFORE sending
        // This provides immediate UI feedback
        _uiState.update {
            it.copy(
                inputText = "",
                inputCursor = 0,
                pendingAttachments = emptyList(),
                mentionState = MentionState.Inactive,
                slashCommandState = SlashCommandState.Inactive
            )
        }

        // Delegate to existing sendMessage with the captured values
        sendMessage(text, attachments)
    }

    // ==================== End Input Text State Functions ====================

    // ==================== Cursor Insertion Functions ====================

    /**
     * Insert text at the current cursor position.
     * Updates both inputText and inputCursor, positioning cursor after inserted text.
     * Dismisses any active mention popup.
     */
    fun insertAtCursor(insertText: String) {
        _uiState.update { state ->
            val currentText = state.inputText
            val cursor = state.inputCursor.coerceIn(0, currentText.length)

            val newText = buildString {
                append(currentText.substring(0, cursor))
                append(insertText)
                append(currentText.substring(cursor))
            }
            val newCursor = cursor + insertText.length

            state.copy(
                inputText = newText,
                inputCursor = newCursor,
                mentionState = MentionState.Inactive,
                slashCommandState = SlashCommandState.Inactive
            )
        }
    }

    /**
     * Insert a vault entry path at the current cursor position.
     * Directories get a trailing slash. All entries get a trailing space.
     * Used by VaultFilePickerScreen for inline path insertion.
     */
    fun insertVaultEntryAtCursor(entry: VaultEntry) {
        val path = if (entry.isDirectory) "${entry.path}/" else entry.path
        insertAtCursor("$path ")
    }

    // ==================== End Cursor Insertion Functions ====================

    // ==================== Mention Autocomplete Functions ====================

    /**
     * Handle input text changes with cursor position for mention detection.
     * Called on every keystroke from ChatInput.
     */
    fun onInputTextChangeWithCursor(newText: String, cursorPosition: Int) {
        val prevState = _uiState.value
        val prevText = prevState.inputText
        val textChanged = newText != prevText

        // Update both input text and cursor position
        _uiState.update { it.copy(inputText = newText, inputCursor = cursorPosition) }

        // If this was a cursor-only move, do not activate new mentions; only dismiss if we moved away.
        if (!textChanged) {
            val mentionState = prevState.mentionState
            if (mentionState is MentionState.Active &&
                !isCursorInMention(newText, cursorPosition, mentionState.mentionStartIndex)
            ) {
                dismissMention()
            }
            val slashState = prevState.slashCommandState
            if (slashState is SlashCommandState.Active &&
                !isCursorInSlashCommand(newText, cursorPosition, slashState.commandStartIndex)
            ) {
                dismissSlashCommand()
            }
            return
        }

        // Detect mention trigger
        val mentionInfo = detectMentionTrigger(newText, cursorPosition)

        when {
            mentionInfo != null -> {
                // Activate or update mention
                activateMention(mentionInfo.startIndex, mentionInfo.query)
            }
            _uiState.value.mentionState is MentionState.Active -> {
                // Check if cursor moved away from mention
                val activeState = _uiState.value.mentionState as MentionState.Active
                if (!isCursorInMention(newText, cursorPosition, activeState.mentionStartIndex)) {
                    dismissMention()
                }
            }
        }

        val slashInfo = detectSlashCommandAutocomplete(newText, cursorPosition)
        when {
            slashInfo != null -> {
                val forceRefresh = prevState.slashCommandState is SlashCommandState.Inactive
                activateSlashCommand(slashInfo.commandStartIndex, slashInfo.query, forceRefresh = forceRefresh)
            }
            _uiState.value.slashCommandState is SlashCommandState.Active -> {
                val activeState = _uiState.value.slashCommandState as SlashCommandState.Active
                if (!isCursorInSlashCommand(newText, cursorPosition, activeState.commandStartIndex)) {
                    dismissSlashCommand()
                }
            }
        }
    }

    private data class MentionInfo(val startIndex: Int, val query: String)

    /**
     * Detect if cursor is at a valid mention trigger position.
     * Returns null if no valid mention, or MentionInfo with the "@" position and query.
     *
     * A valid mention:
     * - Starts with "@" that is at the start of text or preceded by whitespace
     * - Has at least 1 character after "@" (to avoid accidental triggers)
     * - Has no whitespace between "@" and cursor
     */
    private fun detectMentionTrigger(text: String, cursorPosition: Int): MentionInfo? {
        if (cursorPosition == 0 || text.isEmpty()) return null

        // Find the "@" that starts this mention (search backwards from cursor)
        var atIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            val char = text[i]
            when {
                char == '@' -> {
                    atIndex = i
                    break
                }
                char.isWhitespace() -> return null  // Hit whitespace before finding "@"
                // Continue searching backwards through query characters
            }
        }

        if (atIndex == -1) return null

        // Validate "@" is at start of text or preceded by whitespace
        val isValidTrigger = atIndex == 0 || text[atIndex - 1].isWhitespace()
        if (!isValidTrigger) return null

        // Extract query (text between "@" and cursor)
        val query = text.substring(atIndex + 1, cursorPosition)

        // Require at least 1 character after "@" to trigger search
        if (query.isEmpty()) return null

        return MentionInfo(atIndex, query)
    }

    /**
     * Check if cursor is still within the mention region.
     */
    private fun isCursorInMention(text: String, cursorPosition: Int, mentionStartIndex: Int): Boolean {
        // Cursor must be after the "@"
        if (cursorPosition <= mentionStartIndex) return false
        // Check the "@" is still there
        if (mentionStartIndex >= text.length || text[mentionStartIndex] != '@') return false
        // Check no whitespace between "@" and cursor
        val mentionText = text.substring(mentionStartIndex + 1, cursorPosition)
        // Spec: require at least 1 character after "@"
        return mentionText.isNotEmpty() && !mentionText.any { it.isWhitespace() }
    }

    /**
     * Activate or update the mention state and start searching.
     */
    private fun activateMention(startIndex: Int, query: String) {
        // Only show one autocomplete popup at a time.
        dismissSlashCommand()

        // Cancel any pending search
        mentionSearchJob?.cancel()

        _uiState.update {
            it.copy(
                mentionState = MentionState.Active(
                    mentionStartIndex = startIndex,
                    query = query,
                    selectedIndex = 0,
                    isLoading = true,
                    suggestions = emptyList()
                )
            )
        }

        // Start debounced search
        mentionSearchJob = viewModelScope.launch {
            delay(300) // Debounce matching VaultFilePicker
            searchMentionSuggestions(query)
        }
    }

    /**
     * Search for vault entries matching the mention query.
     */
    private suspend fun searchMentionSuggestions(query: String) {
        val currentState = _uiState.value.mentionState
        if (currentState !is MentionState.Active) return

        findVaultEntries(query)
            .onSuccess { entries ->
                // Only update if still searching for same query
                val state = _uiState.value.mentionState
                if (state is MentionState.Active && state.query == query) {
                    val suggestions = entries.take(8) // Limit for dropdown
                    val selectedIndex = if (suggestions.isEmpty()) {
                        0
                    } else {
                        state.selectedIndex.coerceIn(0, suggestions.lastIndex)
                    }
                    _uiState.update {
                        it.copy(
                            mentionState = state.copy(
                                isLoading = false,
                                suggestions = suggestions,
                                selectedIndex = selectedIndex,
                                error = null
                            )
                        )
                    }
                }
            }
            .onFailure { error ->
                val state = _uiState.value.mentionState
                if (state is MentionState.Active && state.query == query) {
                    _uiState.update {
                        it.copy(
                            mentionState = state.copy(
                                isLoading = false,
                                suggestions = emptyList(),
                                selectedIndex = 0,
                                error = error.message
                            )
                        )
                    }
                }
            }
    }

    /**
     * Move the highlighted mention suggestion up/down (keyboard navigation).
     */
    fun moveMentionSelection(delta: Int) {
        val state = _uiState.value.mentionState
        if (state !is MentionState.Active) return
        if (state.suggestions.isEmpty()) return

        val newIndex = (state.selectedIndex + delta).coerceIn(0, state.suggestions.lastIndex)
        if (newIndex == state.selectedIndex) return

        _uiState.update { it.copy(mentionState = state.copy(selectedIndex = newIndex)) }
    }

    /**
     * Select the currently highlighted mention suggestion (Enter/Tab).
     */
    fun selectHighlightedMentionSuggestion() {
        val state = _uiState.value.mentionState
        if (state !is MentionState.Active) return
        val entry = state.suggestions.getOrNull(state.selectedIndex) ?: return
        selectMentionSuggestion(entry)
    }

    /**
     * Select a mention suggestion and insert the path into the text.
     * Replaces "@query" with the vault path and updates cursor position.
     */
    fun selectMentionSuggestion(entry: VaultEntry) {
        val state = _uiState.value.mentionState
        if (state !is MentionState.Active) return

        val currentText = _uiState.value.inputText
        val mentionStart = state.mentionStartIndex

        // Defensive: if the stored start index is stale, bail out and dismiss.
        if (mentionStart !in currentText.indices || currentText[mentionStart] != '@') {
            dismissMention()
            return
        }

        // Find where the mention token ends (scan forward to next whitespace or end-of-text).
        // This avoids partial replacement if the user moves the cursor left within the token.
        val mentionEnd = (mentionStart + 1 until currentText.length)
            .firstOrNull { currentText[it].isWhitespace() }
            ?: currentText.length

        // Replace "@query" with the path (with trailing space for convenience)
        // Note: VaultEntry.path is normalized to have no trailing slash, so we add it for directories
        // to make it visually obvious it's a folder.
        val insertedPath = if (entry.isDirectory) "${entry.path}/" else entry.path
        val replacement = "$insertedPath "
        val newText = buildString {
            append(currentText.substring(0, mentionStart))
            append(replacement)
            if (mentionEnd < currentText.length) {
                append(currentText.substring(mentionEnd))
            }
        }

        // Calculate new cursor position (after the inserted path + space)
        val newCursor = mentionStart + replacement.length

        // Update text, cursor, and dismiss mention
        _uiState.update {
            it.copy(
                inputText = newText,
                inputCursor = newCursor,
                mentionState = MentionState.Inactive
            )
        }
    }

    /**
     * Dismiss the mention popup.
     */
    fun dismissMention() {
        mentionSearchJob?.cancel()
        _uiState.update { it.copy(mentionState = MentionState.Inactive) }
    }

    // ==================== End Mention Autocomplete Functions ====================

    // ==================== Slash Command Autocomplete Functions ====================

    private fun isCursorInSlashCommand(text: String, cursorPosition: Int, commandStartIndex: Int): Boolean {
        if (commandStartIndex != 0) return false
        if (text.isEmpty() || text[0] != '/') return false
        if (cursorPosition <= commandStartIndex) return false

        val tokenEnd = text.indexOfAny(charArrayOf(' ', '\t', '\n', '\r'))
            .let { if (it == -1) text.length else it }
        return cursorPosition <= tokenEnd
    }

    private fun filterSlashCommandSuggestions(query: String): List<CommandInfo> {
        val normalized = query.lowercase()
        val filtered = if (normalized.isEmpty()) {
            knownCommands
        } else {
            knownCommands.filter { it.name.lowercase().startsWith(normalized) }
        }
        return filtered
            .sortedBy { it.name.lowercase() }
            .take(8)
    }

    private fun activateSlashCommand(startIndex: Int, query: String, forceRefresh: Boolean) {
        // Only show one autocomplete popup at a time.
        dismissMention()

        slashCommandSearchJob?.cancel()

        val suggestions = if (commandsLoaded) filterSlashCommandSuggestions(query) else emptyList()
        val isLoading = !commandsLoaded || (forceRefresh && shouldRefreshCommands(Clock.System.now().toEpochMilliseconds()))

        _uiState.update {
            it.copy(
                slashCommandState = SlashCommandState.Active(
                    commandStartIndex = startIndex,
                    query = query,
                    selectedIndex = 0,
                    isLoading = isLoading,
                    suggestions = suggestions,
                    error = null
                )
            )
        }

        if (isLoading) {
            loadCommands(force = true)
        }

        // Debounce in case the user types quickly.
        slashCommandSearchJob = viewModelScope.launch {
            delay(50)
            val state = _uiState.value.slashCommandState
            if (state !is SlashCommandState.Active) return@launch
            if (state.query != query) return@launch

            if (!commandsLoaded && state.isLoading) {
                // If we still haven't loaded commands, keep showing loading state.
                return@launch
            }

            val nextSuggestions = filterSlashCommandSuggestions(query)
            _uiState.update {
                it.copy(
                    slashCommandState = state.copy(
                        isLoading = false,
                        suggestions = nextSuggestions,
                        selectedIndex = if (nextSuggestions.isEmpty()) 0 else state.selectedIndex.coerceIn(0, nextSuggestions.lastIndex),
                        error = null
                    )
                )
            }
        }
    }

    fun moveSlashCommandSelection(delta: Int) {
        val state = _uiState.value.slashCommandState
        if (state !is SlashCommandState.Active) return
        if (state.suggestions.isEmpty()) return

        val newIndex = (state.selectedIndex + delta).coerceIn(0, state.suggestions.lastIndex)
        if (newIndex == state.selectedIndex) return

        _uiState.update { it.copy(slashCommandState = state.copy(selectedIndex = newIndex)) }
    }

    fun selectHighlightedSlashCommandSuggestion() {
        val state = _uiState.value.slashCommandState
        if (state !is SlashCommandState.Active) return
        val cmd = state.suggestions.getOrNull(state.selectedIndex) ?: return
        selectSlashCommandSuggestion(cmd)
    }

    fun selectSlashCommandSuggestion(command: CommandInfo) {
        val state = _uiState.value.slashCommandState
        if (state !is SlashCommandState.Active) return

        val currentText = _uiState.value.inputText
        val start = state.commandStartIndex

        if (start !in currentText.indices || currentText[start] != '/') {
            dismissSlashCommand()
            return
        }

        val tokenEnd = (start until currentText.length)
            .firstOrNull { idx -> idx > start && currentText[idx].isWhitespace() }
            ?: currentText.length

        val replacement = "/${command.name} "
        val remainder = if (tokenEnd < currentText.length) currentText.substring(tokenEnd) else ""
        val cleanedRemainder = if (remainder.isNotEmpty() && remainder.first() == ' ') remainder.dropWhile { it == ' ' } else remainder

        val newText = buildString {
            append(currentText.substring(0, start))
            append(replacement)
            append(cleanedRemainder)
        }

        val newCursor = (start + replacement.length)
            .coerceIn(0, newText.length)

        _uiState.update {
            it.copy(
                inputText = newText,
                inputCursor = newCursor,
                slashCommandState = SlashCommandState.Inactive
            )
        }
    }

    fun dismissSlashCommand() {
        slashCommandSearchJob?.cancel()
        val state = _uiState.value.slashCommandState
        if (state is SlashCommandState.Inactive) return
        _uiState.update { it.copy(slashCommandState = SlashCommandState.Inactive) }
    }

    // ==================== End Slash Command Autocomplete Functions ====================

    /**
     * Subscribe to the locally persisted active session ID.
     * This is the source of truth for the main chat session.
     */
    private fun observeCurrentSessionId() {
        viewModelScope.launch {
            workspaceBootstrap.await()
            appSettings.getCurrentSessionId().collect { newSessionId ->
                val currentId = _uiState.value.currentSessionId

                // If the persisted session id is cleared (e.g. switching to a workspace that has no saved session yet),
                // make sure we don't keep chatting on a stale session while the directory context has already changed.
                if (newSessionId.isNullOrBlank()) {
                    if (currentId != null) {
                        OcMobileLog.d(TAG, "Active session cleared (re-seeding via REST). previousSessionId=$currentId")
                        clearStreamingBuffers()
                        _uiState.update {
                            it.copy(
                                currentSessionId = null,
                                revertMessageId = null,
                                messages = emptyList(),
                                lastGoodMessageId = null,
                                isLoading = true,
                                error = null,
                                pendingPermission = null
                            )
                        }
                        loadCurrentSession(isManualRefresh = false)
                    }
                    return@collect
                }

                OcMobileLog.d(TAG, "Active session changed: newSessionId=$newSessionId, currentId=$currentId, changed=${newSessionId != currentId}")
                if (newSessionId != currentId) {
                    OcMobileLog.d(TAG, "Switching to session: $newSessionId")
                    clearStreamingBuffers()
                    _uiState.update {
                        it.copy(
                            currentSessionId = newSessionId,
                            revertMessageId = null,
                            messages = emptyList(),
                            lastGoodMessageId = null,
                            isLoading = true,
                            error = null,
                            pendingPermission = null
                        )
                    }

                    // Resolve revert pointer first to avoid briefly showing messages that should be hidden,
                    // and to keep derived state (lastGood, context usage, banners) consistent.
                    val session = sessionRepository.getSession(newSessionId).getOrNull()
                    if (newSessionId == _uiState.value.currentSessionId) {
                        val revertMessageId = session?.revert?.messageId
                        _uiState.update { state -> applyRevertPointer(state, revertMessageId) }
                        viewModelScope.launch {
                            contextUsageRepository.updateUsage(_uiState.value.messages)
                        }
                    }

                    ensureLoadMessages(newSessionId)
                    refreshPendingPermissions()
                }
            }
        }
    }

    private fun loadCurrentSession(isManualRefresh: Boolean = false) {
        viewModelScope.launch {
            OcMobileLog.d(TAG, "loadCurrentSession: starting REST call")
            _uiState.update { state ->
                val showBlockingLoading = !isManualRefresh && state.messages.isEmpty()
                state.copy(
                    // Avoid blanking the chat transcript during manual/foreground refresh.
                    isLoading = if (showBlockingLoading) true else state.isLoading,
                    error = null,
                    isRefreshing = if (isManualRefresh) true else state.isRefreshing
                )
            }

            val sessionId = sessionRepository.getCurrentSessionId()
                .getOrElse { error ->
                    if (error is UnauthorizedError) {
                        logoutDueToUnauthorized(error.message)
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = ChatError.ConnectionFailed(error.message),
                            isRefreshing = false
                        )
                    }
                    return@launch
                }

            OcMobileLog.d(TAG, "loadCurrentSession: REST returned sessionId=$sessionId")
            _uiState.update { it.copy(currentSessionId = sessionId, revertMessageId = null) }
            refreshPendingPermissions()

            // Resolve revert pointer before loading messages to avoid transiently rendering hidden messages,
            // and to keep derived state consistent.
            val session = sessionRepository.getSession(sessionId).getOrNull()
            if (sessionId == _uiState.value.currentSessionId) {
                val revertMessageId = session?.revert?.messageId
                _uiState.update { state -> applyRevertPointer(state, revertMessageId) }
                viewModelScope.launch {
                    contextUsageRepository.updateUsage(_uiState.value.messages)
                }
            }

            if (isManualRefresh) {
                val job = ensureLoadMessages(sessionId)
                if (job == null) {
                    _uiState.update { it.copy(isRefreshing = false) }
                } else {
                    job.invokeOnCompletion {
                        viewModelScope.launch {
                            _uiState.update { it.copy(isRefreshing = false) }
                        }
                    }
                }
            } else {
                ensureLoadMessages(sessionId)
            }
        }
    }

    private fun ensureLoadMessages(sessionId: String): kotlinx.coroutines.Job? {
        // Coalesce: if we're already fetching this same session, don't start another request.
        if (loadMessagesInFlightSessionId == sessionId && loadMessagesJob?.isActive == true) {
            return loadMessagesJob
        }

        // Cancel any previous in-flight load to avoid piling up requests.
        loadMessagesJob?.cancel()
        loadMessagesInFlightSessionId = sessionId

        loadMessagesJob = viewModelScope.launch {
            messageRepository.getMessages(sessionId)
                .onSuccess { messages ->
                    // GUARD: Only apply if still on same session (prevents stale write from race)
                    if (sessionId != _uiState.value.currentSessionId) return@onSuccess

                    val visibleMessages = filterMessagesForRevert(messages, _uiState.value.revertMessageId)

                    // Track last good message ID (last assistant message without error)
                    val lastGoodId = visibleMessages.lastOrNull { msg ->
                        msg is AssistantMessage && msg.error == null
                    }?.id

                    // Detect session corruption from message state:
                    // If latest assistant message has an error AND we have a good checkpoint to revert to
                    val latestAssistant = visibleMessages.filterIsInstance<AssistantMessage>().lastOrNull()
                    val latestAssistantError = latestAssistant?.error

                    // NOTE: A user-initiated abort is represented as MessageAbortedError.
                    // Treat it as a normal cancellation: do NOT flag session as corrupted.
                    val isAbortError = latestAssistantError is MessageAbortedError

                    val sessionCorruptedFromMessage =
                        latestAssistantError != null && !isAbortError && lastGoodId != null

                    // DEBUG: Revert tracking
                    OcMobileLog.d(TAG, "[REVERT] loadMessages: messageCount=${messages.size}, " +
                            "visibleCount=${visibleMessages.size}, revertMessageId=${_uiState.value.revertMessageId}, " +
                            "lastGoodId=$lastGoodId, latestAssistantId=${latestAssistant?.id}, " +
                            "latestAssistantError=${latestAssistantError?.let { it::class.simpleName }}," +
                            " latestAssistantErrorMsg=${latestAssistantError?.message}, " +
                            "finishReason=${latestAssistant?.finishReason}, " +
                            "sessionCorruptedFromMessage=$sessionCorruptedFromMessage")
                    // DEBUG: Dump message list with indices
                    visibleMessages.forEachIndexed { index, msg ->
                        val type = if (msg is AssistantMessage) "A" else "U"
                        val error = if (msg is AssistantMessage) msg.error?.let { "ERR" } ?: "ok" else "-"
                        OcMobileLog.d(TAG, "[REVERT] msg[$index] $type $error id=${msg.id.takeLast(8)}")
                    }

                    _uiState.update {
                        // If we were streaming a message, clear streaming ONLY once the
                        // final message (with real parts) has arrived via refetch.
                        // This avoids a flash of an "empty bubble" between streaming and final render.
                        val currentStreamingId = it.streamingMessageId
                        val shouldClearStreaming = currentStreamingId != null &&
                            visibleMessages.any { msg ->
                                msg.id == currentStreamingId &&
                                    (msg as? AssistantMessage)?.parts?.isNotEmpty() == true
                            }

                        it.copy(
                            messages = visibleMessages,
                            isLoading = false,
                            lastGoodMessageId = lastGoodId,  // Don't preserve old - let it be null for new sessions
                            // Set SessionCorrupted if latest assistant message has error (and we have revert point)
                            // Only set if not already set (don't overwrite existing errors)
                            error = when {
                                // If the latest assistant error is an abort, clear any SessionCorrupted banner.
                                isAbortError && it.error is ChatError.SessionCorrupted -> null
                                // If messages are now healthy, clear any lingering SessionCorrupted banner.
                                latestAssistantError == null && it.error is ChatError.SessionCorrupted -> null
                                sessionCorruptedFromMessage && it.error == null -> ChatError.SessionCorrupted(
                                    "Assistant message contains error"
                                )
                                else -> it.error
                            },
                            streamingMessageId = if (shouldClearStreaming) null else it.streamingMessageId
                        )
                    }

                    // Update context usage after messages are loaded
                    contextUsageRepository.updateUsage(visibleMessages)
                }
                .onFailure { error ->
                    // GUARD: Also guard failure path
                    if (sessionId != _uiState.value.currentSessionId) return@onFailure

                    if (error is UnauthorizedError) {
                        viewModelScope.launch {
                            logoutDueToUnauthorized(error.message)
                        }
                        return@onFailure
                    }

                    // Reset context usage if messages were cleared (session switch)
                    if (_uiState.value.messages.isEmpty()) {
                        contextUsageRepository.updateUsage(emptyList())
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = ChatError.LoadFailed(error.message)
                        )
                    }
                }
        }

        return loadMessagesJob
    }

    private fun subscribeToEvents() {
        viewModelScope.launch {
            // Observe connection state for UI display
            launch {
                eventStream.connectionState.collect { state ->
                    val previousState = _uiState.value.connectionState
                    OcMobileLog.d(TAG, "Connection state: $previousState -> $state")

                    _uiState.update {
                        it.copy(
                            connectionState = when (state) {
                                ConnectionState.CONNECTED -> ChatConnectionState.Connected
                                ConnectionState.DISCONNECTED -> ChatConnectionState.Disconnected
                                ConnectionState.RECONNECTING -> ChatConnectionState.Reconnecting
                            }
                        )
                    }
                }
            }

            // Collect events (EventStreamImpl handles reconnection internally)
            workspaceBootstrap.await()
            eventStream.subscribeToEvents().collect { event ->
                OcMobileLog.d(TAG) { "Event received: ${event::class.simpleName}" }
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: com.ratulsarna.ocmobile.domain.model.Event) {
        when (event) {
            is MessagePartUpdatedEvent -> handleMessagePartUpdate(event)
            is MessageUpdatedEvent -> handleMessageUpdated(event)
            is MessageRemovedEvent -> handleMessageRemoved(event)
            is SessionStatusEvent -> handleSessionStatus(event)
            is SessionUpdatedEvent -> handleSessionUpdated(event)
            is PermissionAskedEvent -> handlePermissionAsked(event)
            is PermissionRepliedEvent -> handlePermissionReplied(event)
            // NOTE: SessionCreatedEvent is NOT handled here anymore.
            // Session switching is now handled by SamSessionIdStream which provides
            // the authoritative main chat session ID. SessionCreatedEvent fires for
            // ALL sessions (including small tasks), so it's not the right signal.
            else -> { /* Ignore other events */ }
        }
    }

    private fun handlePermissionAsked(event: PermissionAskedEvent) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val request = PermissionRequest(
            requestId = event.requestId,
            sessionId = event.sessionId,
            permission = event.permission,
            patterns = event.patterns,
            always = event.always,
            toolMessageId = event.toolMessageId,
            toolCallId = event.toolCallId
        )
        val existing = permissionRequestsById[event.requestId]
        permissionRequestsById[event.requestId] = PermissionEntry(
            request = request,
            addedAtMs = existing?.addedAtMs ?: nowMs
        )
        updatePendingPermissionInState()
    }

    private fun handlePermissionReplied(event: PermissionRepliedEvent) {
        permissionRequestsById.remove(event.requestId)
        updatePendingPermissionInState()
    }

    fun replyToPermissionRequest(requestId: String, reply: PermissionReply, message: String? = null) {
        viewModelScope.launch {
            val existing = permissionRequestsById[requestId] ?: return@launch
            val orderingSnapshot = permissionRequestsById.keys.toList()

            permissionRequestsById.remove(requestId)
            updatePendingPermissionInState()

            permissionRepository.reply(requestId = requestId, reply = reply, message = message)
                .onFailure { e ->
                    if (!permissionRequestsById.containsKey(requestId)) {
                        restorePermissionEntry(orderingSnapshot, requestId, existing)
                        updatePendingPermissionInState()
                    }
                    _uiState.update { it.copy(error = ChatError.PermissionReplyFailed(e.message)) }
                    // Reconcile with server state in case the server processed the reply but we hit a network error.
                    refreshPendingPermissions()
                }
        }
    }

    fun replyToPermissionRequest(requestId: String, reply: String, message: String? = null) {
        val parsed = when (reply.lowercase()) {
            "once" -> PermissionReply.ONCE
            "always" -> PermissionReply.ALWAYS
            "reject" -> PermissionReply.REJECT
            else -> null
        }

        if (parsed == null) {
            OcMobileLog.w(TAG, "Invalid permission reply: requestId=$requestId reply=$reply")
            _uiState.update { it.copy(error = ChatError.PermissionReplyFailed("Invalid permission reply: $reply")) }
            return
        }

        replyToPermissionRequest(requestId = requestId, reply = parsed, message = message)
    }

    private fun restorePermissionEntry(
        orderingSnapshot: List<String>,
        requestId: String,
        entry: PermissionEntry
    ) {
        if (permissionRequestsById.containsKey(requestId)) return

        val rebuilt = LinkedHashMap<String, PermissionEntry>()
        var inserted = false

        // Rebuild in the original order snapshot, skipping keys that no longer exist.
        orderingSnapshot.forEach { id ->
            if (id == requestId) {
                rebuilt[id] = entry
                inserted = true
                return@forEach
            }
            val current = permissionRequestsById[id]
            if (current != null) rebuilt[id] = current
        }

        if (!inserted) {
            rebuilt[requestId] = entry
        }

        // Append any new entries that arrived after the snapshot.
        permissionRequestsById.forEach { (id, current) ->
            if (!rebuilt.containsKey(id)) rebuilt[id] = current
        }

        permissionRequestsById.clear()
        permissionRequestsById.putAll(rebuilt)
    }

    private fun handleMessagePartUpdate(event: MessagePartUpdatedEvent) {
        // Only process events for current session
        val currentId = _uiState.value.currentSessionId
        OcMobileLog.d(TAG) {
            "MessagePartUpdate: event.sessionId=${event.sessionId}, currentSessionId=$currentId, match=${event.sessionId == currentId}"
        }
        if (event.sessionId != currentId) {
            OcMobileLog.d(TAG) { "FILTERED OUT - session ID mismatch" }
            return
        }

        // Snapshots are internal (not rendered) and often arrive before any user-visible parts.
        // If we create a placeholder message from a snapshot, the UI shows an empty bubble.
        if (event.part is com.ratulsarna.ocmobile.domain.model.SnapshotPart) {
            return
        }

        // Read current state to make decisions BEFORE performing side effects.
        // This is safe under single-threaded Main dispatcher execution.
        val currentState = _uiState.value
        val existingIndex = currentState.messages.indexOfFirst { it.id == event.messageId }
        val existingMessage = existingIndex.takeIf { it >= 0 }?.let { currentState.messages[it] }

        val knownRole = messageRoleById[event.messageId]
        val shouldRenderNow = existingMessage != null ||
            knownRole != null ||
            isDefinitelyAssistantPart(event.part) ||
            // Text streaming (delta) is effectively always assistant output; don't block rendering
            // on waiting for message.updated.
            event.delta != null

        // Perform buffer operations OUTSIDE the StateFlow.update lambda to avoid side effects
        // that could cause issues if the lambda were re-executed (CAS retry).
        if (!shouldRenderNow) {
            val buffered = bufferedPartsByMessageId.getOrPut(event.messageId) { mutableListOf() }
            val merged = mergeStreamingPart(
                existing = buffered,
                incoming = event.part,
                delta = event.delta
            )
            bufferedPartsByMessageId[event.messageId] = merged.toMutableList()
            return // No state update needed when buffering
        }

        // Extract buffered parts for new messages BEFORE the update block
        val initialPartsForNewMessage = if (existingMessage == null) {
            bufferedPartsByMessageId.remove(event.messageId)?.toList().orEmpty()
        } else {
            emptyList()
        }

        // Check and clear pending user message tracking BEFORE the update block
        val shouldClearPendingParts = existingMessage is UserMessage &&
            userMessagesAwaitingServerParts.contains(event.messageId)
        if (shouldClearPendingParts) {
            userMessagesAwaitingServerParts.remove(event.messageId)
        }

        val placeholderRole = knownRole ?: if (isDefinitelyAssistantPart(event.part) || existingMessage is AssistantMessage) {
            "assistant"
        } else if (event.delta != null) {
            "assistant"
        } else {
            "user"
        }

        _uiState.update { state ->
            // Re-read message index from current state (in case it changed)
            val stateExistingIndex = state.messages.indexOfFirst { it.id == event.messageId }
            val stateExistingMessage = stateExistingIndex.takeIf { it >= 0 }?.let { state.messages[it] }

            // If we previously reconciled a pending user message to the server message ID (via message.updated)
            // and kept the optimistic parts temporarily, drop those pending parts once the server starts
            // emitting real parts; otherwise we show duplicated user text (pending text + server text).
            val baseMessages = if (shouldClearPendingParts && stateExistingMessage is UserMessage) {
                val cleaned = stateExistingMessage.copy(
                    parts = stateExistingMessage.parts.filterNot { (it.id ?: "").startsWith("pending-") }
                )
                val list = state.messages.toMutableList()
                list[stateExistingIndex] = cleaned
                list.toList()
            } else {
                state.messages
            }

            val updatedMessages = upsertStreamingPart(
                messages = baseMessages,
                sessionId = event.sessionId,
                messageId = event.messageId,
                incomingPart = event.part,
                delta = event.delta,
                placeholderRole = placeholderRole,
                initialPartsForNewMessage = initialPartsForNewMessage
            )

            val updatedMessage = updatedMessages.firstOrNull { it.id == event.messageId }
            val updatedIsAssistant = updatedMessage is AssistantMessage

            state.copy(
                messages = filterMessagesForRevert(updatedMessages, state.revertMessageId),
                // Only mark streaming for assistant messages. OpenCode can emit part updates for
                // user messages too (e.g., server-side augmentation).
                streamingMessageId = if (updatedIsAssistant) event.messageId else state.streamingMessageId
            )
        }
    }

    private fun handleMessageUpdated(event: MessageUpdatedEvent) {
        val currentId = _uiState.value.currentSessionId
        if (event.sessionId != currentId) return

        messageRoleById[event.messageId] = event.role

        // Extract buffered parts BEFORE the update block to avoid side effects inside StateFlow.update lambda
        val bufferedParts = bufferedPartsByMessageId.remove(event.messageId)?.toList().orEmpty()

        _uiState.update { state ->

            val existingIndex = state.messages.indexOfFirst { it.id == event.messageId }
            val existing = existingIndex.takeIf { it >= 0 }?.let { state.messages[it] }

            val serverText = bufferedParts.filterIsInstance<TextPart>()
                .joinToString("\n") { it.text }
                .takeIf { it.isNotBlank() }

            fun buildMessage(role: String): Message {
                return if (role == "user") {
                    UserMessage(
                        id = event.messageId,
                        sessionId = event.sessionId,
                        createdAt = event.createdAt,
                        parts = bufferedParts
                    )
                } else {
                    AssistantMessage(
                        id = event.messageId,
                        sessionId = event.sessionId,
                        createdAt = event.createdAt,
                        parts = bufferedParts
                    )
                }
            }

            val desiredRoleIsUser = event.role == "user"

            val updatedMessages = when {
                existing != null -> {
                    val updated = when {
                        desiredRoleIsUser && existing is UserMessage -> existing.copy(
                            createdAt = event.createdAt,
                            parts = bufferedParts.ifEmpty { existing.parts }
                        )
                        !desiredRoleIsUser && existing is AssistantMessage -> existing.copy(
                            createdAt = event.createdAt,
                            parts = bufferedParts.ifEmpty { existing.parts }
                        )
                        else -> {
                            // Role mismatch (or placeholder guessed wrong) - replace message type but preserve parts.
                            val parts = bufferedParts.ifEmpty { existing.parts }
                            if (desiredRoleIsUser) {
                                UserMessage(
                                    id = existing.id,
                                    sessionId = existing.sessionId,
                                    createdAt = event.createdAt,
                                    parts = parts
                                )
                            } else {
                                AssistantMessage(
                                    id = existing.id,
                                    sessionId = existing.sessionId,
                                    createdAt = event.createdAt,
                                    parts = parts
                                )
                            }
                        }
                    }
                    state.messages.toMutableList().apply { set(existingIndex, updated) }
                }
                desiredRoleIsUser -> {
                    val optimisticIndex = findMatchingOptimisticUserMessageIndex(state.messages, serverText)
                    if (optimisticIndex != null) {
                        val optimistic = state.messages[optimisticIndex] as UserMessage
                        val updated = UserMessage(
                            id = event.messageId,
                            sessionId = event.sessionId,
                            createdAt = event.createdAt,
                            parts = bufferedParts.ifEmpty { optimistic.parts }
                        )
                        if (bufferedParts.isEmpty()) {
                            userMessagesAwaitingServerParts.add(event.messageId)
                        } else {
                            userMessagesAwaitingServerParts.remove(event.messageId)
                        }
                        state.messages.toMutableList().apply { set(optimisticIndex, updated) }
                    } else if (bufferedParts.isNotEmpty()) {
                        userMessagesAwaitingServerParts.remove(event.messageId)
                        state.messages + buildMessage("user")
                    } else {
                        // No optimistic message to reconcile, and no parts to show yet.
                        state.messages
                    }
                }
                else -> {
                    // For assistant messages, don't insert a new message until we have at least one
                    // user-visible part. Otherwise we show an empty bubble while the first parts stream.
                    if (bufferedParts.isNotEmpty()) {
                        state.messages + buildMessage("assistant")
                    } else {
                        state.messages
                    }
                }
            }

            state.copy(messages = filterMessagesForRevert(updatedMessages, state.revertMessageId))
        }
    }

    private fun handleMessageRemoved(event: MessageRemovedEvent) {
        val currentId = _uiState.value.currentSessionId
        if (event.sessionId != currentId) return

        messageRoleById.remove(event.messageId)
        bufferedPartsByMessageId.remove(event.messageId)
        userMessagesAwaitingServerParts.remove(event.messageId)

        _uiState.update { state ->
            val updated = state.messages.filterNot { it.id == event.messageId }
            val selectionIsRemoved = state.selectedMessageForAction?.id == event.messageId
            // If the removed message was anchoring a revert, clear it (server cleanup clears revert state).
            val nextRevertId = state.revertMessageId?.takeIf { it != event.messageId }
            val shouldClearStreaming = state.streamingMessageId == event.messageId
            val shouldClearLastGood = state.lastGoodMessageId == event.messageId
            state.copy(
                revertMessageId = nextRevertId,
                messages = filterMessagesForRevert(updated, nextRevertId),
                streamingMessageId = if (shouldClearStreaming) null else state.streamingMessageId,
                lastGoodMessageId = if (shouldClearLastGood) null else state.lastGoodMessageId,
                selectedMessageForAction = if (selectionIsRemoved) null else state.selectedMessageForAction,
                showRevertConfirmation = if (selectionIsRemoved) false else state.showRevertConfirmation
            )
        }
    }

    private fun handleSessionUpdated(event: SessionUpdatedEvent) {
        val currentId = _uiState.value.currentSessionId
        if (event.session.id != currentId) return

        val revertMessageId = event.session.revert?.messageId
        val previousRevertMessageId = _uiState.value.revertMessageId
        _uiState.update { state -> applyRevertPointer(state, revertMessageId) }
        viewModelScope.launch {
            contextUsageRepository.updateUsage(_uiState.value.messages)
        }

        // If the session was "unreverted" (or cleaned up) elsewhere, we may need to refetch so hidden
        // messages can re-appear (since we don't retain an unfiltered backing list).
        if (previousRevertMessageId != null && revertMessageId == null) {
            ensureLoadMessages(event.session.id)
        }
    }

    private fun handleSessionStatus(event: SessionStatusEvent) {
        // Only process events for current session
        val currentId = _uiState.value.currentSessionId
        OcMobileLog.d(TAG) {
            "SessionStatus: event.sessionId=${event.sessionId}, status=${event.status}, currentSessionId=$currentId, match=${event.sessionId == currentId}"
        }
        if (event.sessionId != currentId) {
            OcMobileLog.d(TAG) { "FILTERED OUT - session ID mismatch" }
            return
        }

        val shouldRefetch = event.status == SessionStatus.IDLE || event.status == SessionStatus.ERROR

        _uiState.update { state ->
            state.copy(
                sessionStatus = when (event.status) {
                    SessionStatus.RUNNING -> SessionUiStatus.Processing
                    SessionStatus.IDLE -> SessionUiStatus.Idle
                    SessionStatus.ERROR -> SessionUiStatus.Error
                },
                // IMPORTANT:
                // Do NOT set ChatError.SessionCorrupted directly from session status.
                // We derive user-visible error banners from message state (via loadMessages),
                // which can distinguish user-initiated aborts (MessageAbortedError) from real failures.
                error = state.error,
                // Clear streaming when the server reports the session is done (idle or error).
                // We now stream full message parts (including tools/steps), so we don't need to
                // keep a separate "delta" rendering buffer across the refetch boundary.
                streamingMessageId = if (shouldRefetch) null else state.streamingMessageId
            )
        }

        if (shouldRefetch) {
            // Streaming is done; any optimistic reconciliation should be superseded by refetched messages.
            userMessagesAwaitingServerParts.clear()
        }

        // Reload messages on IDLE (success) or ERROR (might have partial message)
        if (shouldRefetch) {
            _uiState.value.currentSessionId?.let { sessionId ->
                ensureLoadMessages(sessionId)
            }
        }
    }

    private fun upsertStreamingPart(
        messages: List<Message>,
        sessionId: String,
        messageId: String,
        incomingPart: com.ratulsarna.ocmobile.domain.model.MessagePart,
        delta: String?,
        placeholderRole: String,
        initialPartsForNewMessage: List<com.ratulsarna.ocmobile.domain.model.MessagePart> = emptyList()
    ): List<Message> {
        val messageIndex = messages.indexOfFirst { it.id == messageId }

        val mergedPart = mergeStreamingPart(
            existing = if (messageIndex >= 0) messages[messageIndex].parts else initialPartsForNewMessage,
            incoming = incomingPart,
            delta = delta
        )

        return if (messageIndex >= 0) {
            val existingMessage = messages[messageIndex]
            val updatedMessage = when (existingMessage) {
                is UserMessage -> existingMessage.copy(parts = mergedPart)
                is AssistantMessage -> existingMessage.copy(parts = mergedPart)
            }
            messages.toMutableList().apply { set(messageIndex, updatedMessage) }
        } else {
            val nowMillis = Clock.System.now().toEpochMilliseconds()
            val createdAt = Instant.fromEpochMilliseconds(nowMillis)
            messages + if (placeholderRole == "user") {
                UserMessage(
                    id = messageId,
                    sessionId = sessionId,
                    createdAt = createdAt,
                    parts = mergedPart
                )
            } else {
                AssistantMessage(
                    id = messageId,
                    sessionId = sessionId,
                    createdAt = createdAt,
                    parts = mergedPart
                )
            }
        }
    }

    private fun isDefinitelyAssistantPart(part: com.ratulsarna.ocmobile.domain.model.MessagePart): Boolean {
        return when (part) {
            is com.ratulsarna.ocmobile.domain.model.ToolPart,
            is com.ratulsarna.ocmobile.domain.model.ReasoningPart,
            is com.ratulsarna.ocmobile.domain.model.StepStartPart,
            is com.ratulsarna.ocmobile.domain.model.StepFinishPart,
            is com.ratulsarna.ocmobile.domain.model.PatchPart,
            is com.ratulsarna.ocmobile.domain.model.AgentPart,
            is com.ratulsarna.ocmobile.domain.model.RetryPart,
            is com.ratulsarna.ocmobile.domain.model.CompactionPart,
            is com.ratulsarna.ocmobile.domain.model.UnknownPart -> true

            // Text/File/Snapshot can belong to user or assistant; don't assume.
            is TextPart,
            is FilePart,
            is com.ratulsarna.ocmobile.domain.model.SnapshotPart -> false
        }
    }

    private fun findMatchingOptimisticUserMessageIndex(messages: List<Message>, serverText: String?): Int? {
        val pending = messages.withIndex()
            .filter { (_, msg) -> msg is UserMessage && msg.id.startsWith("pending-") }
        if (pending.isEmpty()) return null

        if (!serverText.isNullOrBlank()) {
            val serverNorm = serverText.trim().lowercase()
            val match = pending.firstOrNull { (_, msg) ->
                val pendingText = extractPlainText(msg).trim().lowercase()
                pendingText.isNotBlank() && (serverNorm.contains(pendingText) || pendingText.contains(serverNorm))
            }
            if (match != null) return match.index
        }

        // Fallback: replace the oldest pending message (events typically arrive in send order).
        return pending.first().index
    }

    private fun extractPlainText(message: Message): String {
        return message.parts.filterIsInstance<TextPart>()
            .joinToString("\n") { it.text }
    }

    private fun mergeStreamingPart(
        existing: List<com.ratulsarna.ocmobile.domain.model.MessagePart>,
        incoming: com.ratulsarna.ocmobile.domain.model.MessagePart,
        delta: String?
    ): List<com.ratulsarna.ocmobile.domain.model.MessagePart> {
        if (incoming is com.ratulsarna.ocmobile.domain.model.SnapshotPart) {
            return existing
        }

        val partId = incoming.id
        val existingIndex = partId?.let { id -> existing.indexOfFirst { it.id == id } } ?: -1
        val previous = if (existingIndex >= 0) existing[existingIndex] else null

        val resolved = when (incoming) {
            is TextPart -> incoming.copy(text = mergeStreamingText(previous as? TextPart, incoming.text, delta))
            is com.ratulsarna.ocmobile.domain.model.ReasoningPart ->
                incoming.copy(text = mergeStreamingText(previous as? com.ratulsarna.ocmobile.domain.model.ReasoningPart, incoming.text, delta))
            else -> incoming
        }

        return if (existingIndex >= 0) {
            existing.toMutableList().apply { set(existingIndex, resolved) }
        } else {
            existing + resolved
        }
    }

    private fun mergeStreamingText(
        previous: Any?,
        incomingText: String,
        delta: String?
    ): String {
        val previousText = when (previous) {
            is TextPart -> previous.text
            is com.ratulsarna.ocmobile.domain.model.ReasoningPart -> previous.text
            else -> ""
        }

        if (!delta.isNullOrEmpty()) {
            val base = if (incomingText.length >= previousText.length) incomingText else previousText
            // Idempotent: if either side already includes the delta suffix, don't append again.
            if (base.endsWith(delta) || previousText.endsWith(delta) || incomingText.endsWith(delta)) return base
            return base + delta
        }

        // Prefer authoritative full text if it looks like a monotonic update.
        if (incomingText.isNotEmpty()) {
            if (incomingText.length >= previousText.length && incomingText.startsWith(previousText)) {
                return incomingText
            }
            if (previousText.length >= incomingText.length && previousText.startsWith(incomingText)) {
                return previousText
            }
        }

        // Last resort: keep whichever looks more complete.
        return if (incomingText.length >= previousText.length) incomingText else previousText
    }

    /**
     * Send a message to the current session.
     * Supports text-only messages for backward compatibility.
     */
    fun sendMessage(text: String) {
        sendMessage(text, emptyList())
    }

    /**
     * Send a message with optional text and/or attachments to the current session.
     */
    fun sendMessage(text: String, attachments: List<Attachment>) {
        val sessionId = _uiState.value.currentSessionId ?: return
        if (text.isBlank() && attachments.isEmpty()) return

        val textToSend = text.trimEnd()
        val slashInvocation = parseSlashCommandInvocation(textToSend)

        // Build message parts for optimistic UI
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val messageSeq = ++pendingMessageCounter
        val parts = buildList {
            if (textToSend.isNotBlank()) {
                add(TextPart(id = "pending-text-$nowMillis-$messageSeq", text = textToSend))
            }
            attachments.forEachIndexed { idx, att ->
                add(
                    FilePart(
                        id = "pending-file-$nowMillis-$messageSeq-$idx",
                        mime = att.mimeType,
                        filename = att.filename,
                        url = ""  // Not encoded for optimistic UI - FilePartView only shows filename
                    )
                )
            }
        }

        val optimisticMessage = UserMessage(
            id = "pending-$nowMillis-$messageSeq",
            sessionId = sessionId,
            createdAt = Instant.fromEpochMilliseconds(nowMillis),
            parts = parts
        )

        // Clear pending attachments and show optimistic message
        _uiState.update {
            it.copy(
                // Sending a new message implicitly commits the revert (OpenCode cleans up reverted messages on resume).
                // Clear the revert pointer locally so the new optimistic message isn't hidden.
                revertMessageId = null,
                messages = it.messages + optimisticMessage,
                pendingAttachments = emptyList()
            )
        }

        viewModelScope.launch {
            pendingSendFailureJob?.cancel()
            pendingSendFailureJob = null
            _uiState.update { it.copy(isSending = true, error = null) }

            var agent = currentAgent
            var model = currentModel?.let { ModelSpec(it.providerId, it.modelId) }

            // On first connect, Settings may still be fetching models/agents. Trigger default selection
            // before sending so we don't hit server-side validation errors (and confusing client errors).
            if (model == null) {
                modelRepository.getConnectedProviders()
                    .onFailure { e -> OcMobileLog.w(TAG, "Failed to initialize default model: ${e.message}") }

                model = appSettings.getSelectedModelSnapshot()
                    ?.let { selected -> ModelSpec(selected.providerId, selected.modelId) }
            }

            if (agent.isNullOrBlank()) {
                agentRepository.getAgents()
                    .onFailure { e -> OcMobileLog.w(TAG, "Failed to initialize default agent: ${e.message}") }

                agent = agentRepository.getSelectedAgent().first()
            }

            val variant = resolvePromptVariant(model)
            val isKnownSlashCommand = if (slashInvocation != null) {
                // Ensure we have a command list before deciding whether to treat it as a command.
                // Also refresh once if we don't recognize the command (allows picking up new commands).
                if (!commandsLoaded || !knownCommandNames.contains(slashInvocation.name)) {
                    fetchCommands(force = true)
                }
                knownCommandNames.contains(slashInvocation.name)
            } else {
                false
            }

            val result = when {
                isKnownSlashCommand -> {
                    messageRepository.sendCommand(
                        sessionId = sessionId,
                        command = slashInvocation!!.name,
                        arguments = slashInvocation.arguments,
                        attachments = attachments,
                        model = model,
                        variant = variant,
                        agent = agent
                    )
                }
                attachments.isEmpty() -> {
                    messageRepository.sendMessage(sessionId, textToSend, model = model, variant = variant, agent = agent)
                }
                else -> {
                    messageRepository.sendMessageWithAttachments(
                        sessionId = sessionId,
                        text = textToSend.takeIf { it.isNotBlank() },
                        attachments = attachments,
                        model = model,
                        variant = variant,
                        agent = agent
                    )
                }
            }

            result
                .onSuccess { response ->
                    // NOTE: We no longer call updateCurrentSessionId here.
                    // The server manages session ID persistence, and SamSessionIdStream
                    // will notify us when the main chat session changes.

                    _uiState.update {
                        it.copy(
                            isSending = false,
                            // Guard: only update lastGoodMessageId if response has no error
                            lastGoodMessageId = if (response.error == null) response.id else it.lastGoodMessageId,
                            // Update local state to actual session (handles session cycling)
                            currentSessionId = response.sessionId,
                            // After the user resumes, OpenCode cleans up reverted messages and clears the pointer.
                            revertMessageId = null
                        )
                    }
                    // Reload using RESPONSE session ID (not captured sessionId)
                    // Handles session cycling where backend may have switched sessions
                    ensureLoadMessages(response.sessionId)
                }
                .onFailure { error ->
                    OcMobileLog.e(
                        TAG,
                        "[SEND] <<< FAILED: sessionId=$sessionId, optimisticId=${optimisticMessage.id}, " +
                            "streamingMessageId=${_uiState.value.streamingMessageId}, " +
                            "sessionStatus=${_uiState.value.sessionStatus}, " +
                            "type=${error::class.simpleName}, message=${error.message}",
                        error
                    )

                    if (isTransientSendNetworkError(error)) {
                        // iOS can drop the underlying HTTP connection when the app backgrounds.
                        // The server may still have received the message, and SSE will reconcile it.
                        // Avoid surfacing a hard send failure immediately.
                        _uiState.update { it.copy(isSending = false) }

                        val optimisticId = optimisticMessage.id
                        val restoreText = text
                        val restoreAttachments = attachments
                        val errorMessage = error.message

                        pendingSendFailureJob?.cancel()
                        pendingSendFailureJob = viewModelScope.launch {
                            val startMs = Clock.System.now().toEpochMilliseconds()
                            while (isActive) {
                                val state = _uiState.value
                                if (state.currentSessionId != sessionId) return@launch

                                val stillPending = state.messages.any { msg -> msg.id == optimisticId }
                                if (!stillPending) return@launch

                                val hasProgress =
                                    state.sessionStatus == SessionUiStatus.Processing || state.streamingMessageId != null
                                if (hasProgress) return@launch

                                val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
                                if (elapsed >= SEND_FAILURE_GRACE_MS) break
                                delay(2_000)
                            }

                            val state = _uiState.value
                            if (state.currentSessionId != sessionId) return@launch
                            if (!state.messages.any { msg -> msg.id == optimisticId }) return@launch
                            if (state.sessionStatus == SessionUiStatus.Processing || state.streamingMessageId != null) return@launch

                            _uiState.update {
                                it.copy(
                                    isSending = false,
                                    messages = it.messages.filterNot { msg -> msg.id == optimisticId },
                                    error = ChatError.SendFailed(errorMessage),
                                    // Restore input text and attachments on failure
                                    inputText = restoreText,
                                    pendingAttachments = restoreAttachments
                                )
                            }
                        }
                        return@onFailure
                    }

                    _uiState.update {
                        it.copy(
                            isSending = false,
                            messages = it.messages.filterNot { msg -> msg.id == optimisticMessage.id },
                            error = ChatError.SendFailed(error.message),
                            // Restore input text and attachments on failure
                            inputText = text,
                            pendingAttachments = attachments
                        )
                    }
                }
        }
    }

    /**
     * Revert to the last known good message by forking the session.
     * @deprecated Use forkFromMessage or revertToMessage instead for explicit choice.
     */
    fun revertToLastGood() {
        val messageId = _uiState.value.lastGoodMessageId
        OcMobileLog.d(TAG, "[ACTION] revertToLastGood called: lastGoodMessageId=$messageId")
        if (messageId == null) {
            OcMobileLog.w(TAG, "[ACTION] revertToLastGood: No lastGoodMessageId available, aborting")
            return
        }
        // Default to showing the action choice dialog instead of immediately forking
        onMessageLongPressById(messageId)
    }

    // ==================== Message Action Sheet Functions ====================

    /**
     * Show the message action sheet for a long-pressed message.
     */
    fun onMessageLongPress(message: Message) {
        val messageType = if (message is UserMessage) "UserMessage" else "AssistantMessage"
        val messageIndex = _uiState.value.messages.indexOfFirst { it.id == message.id }
        val totalMessages = _uiState.value.messages.size
        OcMobileLog.d(TAG, "[ACTION] onMessageLongPress: type=$messageType, " +
                "messageId=${message.id}, index=$messageIndex/$totalMessages, " +
                "sessionId=${_uiState.value.currentSessionId}")
        _uiState.update { it.copy(selectedMessageForAction = message) }
    }

    /**
     * Show the message action sheet for a message.
     * Used by platforms that trigger actions via an explicit UI affordance (e.g. "…" button).
     */
    fun showMessageActions(message: Message) {
        onMessageLongPress(message)
    }

    /**
     * Show the message action sheet for a message by ID.
     * Used when triggered from error banner (uses lastGoodMessageId).
     */
    private fun onMessageLongPressById(messageId: String) {
        OcMobileLog.d(TAG, "[ACTION] onMessageLongPressById: looking for messageId=$messageId")
        val message = _uiState.value.messages.find { it.id == messageId }
        if (message != null) {
            OcMobileLog.d(TAG, "[ACTION] onMessageLongPressById: found message, showing action sheet")
            onMessageLongPress(message)
        } else {
            OcMobileLog.w(TAG, "[ACTION] onMessageLongPressById: message NOT FOUND in current messages list")
        }
    }

    /**
     * Dismiss the message action sheet.
     */
    fun dismissMessageAction() {
        val wasShowingSheet = _uiState.value.selectedMessageForAction != null
        val wasShowingConfirmation = _uiState.value.showRevertConfirmation
        OcMobileLog.d(TAG, "[ACTION] dismissMessageAction: wasShowingSheet=$wasShowingSheet, " +
                "wasShowingConfirmation=$wasShowingConfirmation")
        _uiState.update { it.copy(selectedMessageForAction = null, showRevertConfirmation = false) }
    }

    /**
     * Show the revert confirmation dialog.
     * Called when user selects "Revert" from the action sheet.
     */
    fun showRevertConfirmation() {
        val message = _uiState.value.selectedMessageForAction
        val messagesAfter = getMessagesAfterSelectedCount()
        OcMobileLog.d(TAG, "[REVERT] showRevertConfirmation: messageId=${message?.id}, " +
                "messagesAfterCount=$messagesAfter")
        _uiState.update { it.copy(showRevertConfirmation = true) }
    }

    private fun resolveRevertTargetMessageId(state: ChatUiState): String? {
        val selected = state.selectedMessageForAction ?: return null
        if (selected is UserMessage) return selected.id

        val selectedIndex = state.messages.indexOfFirst { it.id == selected.id }
        if (selectedIndex <= 0) return null

        for (idx in selectedIndex downTo 0) {
            val msg = state.messages[idx]
            if (msg is UserMessage) return msg.id
        }

        return null
    }

    /**
     * Cancel the revert confirmation.
     */
    fun cancelRevert() {
        OcMobileLog.d(TAG, "[REVERT] cancelRevert: user cancelled revert confirmation")
        dismissMessageAction()
    }

    /**
     * Execute the revert after confirmation.
     * Calls the revert API to set the session's "revert pointer".
     * OpenCode hides messages after that point immediately, but may only physically delete them later (cleanup).
     */
    fun confirmRevert() {
        if (_uiState.value.isReverting) return

        val stateSnapshot = _uiState.value
        val sessionId = _uiState.value.currentSessionId
        val selected = _uiState.value.selectedMessageForAction
        val targetMessageId = resolveRevertTargetMessageId(stateSnapshot)
        val messagesAfter = getMessagesAfterSelectedCount()

        OcMobileLog.d(TAG, "[REVERT] confirmRevert called: sessionId=$sessionId, " +
                "selectedMessageId=${selected?.id}, targetMessageId=$targetMessageId, messagesAfterCount=$messagesAfter")

        if (sessionId == null) {
            OcMobileLog.e(TAG, "[REVERT] confirmRevert ABORTED: sessionId is null")
            dismissMessageAction()
            return
        }
        if (targetMessageId == null) {
            OcMobileLog.e(TAG, "[REVERT] confirmRevert ABORTED: could not resolve revert target message")
            dismissMessageAction()
            return
        }

        val messagesSnapshot = stateSnapshot.messages

        viewModelScope.launch {
            OcMobileLog.d(TAG, "[REVERT] >>> Starting revert API call: sessionId=$sessionId, " +
                    "messageId=$targetMessageId, will hide $messagesAfter messages")
            _uiState.update { it.copy(isReverting = true, error = null) }

            val startTime = Clock.System.now().toEpochMilliseconds()
            sessionRepository.revertSession(sessionId, targetMessageId)
                .onSuccess { revertedSession ->
                    val duration = Clock.System.now().toEpochMilliseconds() - startTime
                    OcMobileLog.d(TAG, "[REVERT] <<< Revert SUCCESS in ${duration}ms: " +
                            "sessionId=${revertedSession.id}, title=${revertedSession.title}")

                    val revertMessageId = revertedSession.revert?.messageId ?: targetMessageId
                    val restoredPrompt = promptTextForRevert(messagesSnapshot, revertMessageId)
                    _uiState.update {
                        val nextInputText = restoredPrompt ?: it.inputText
                        it.copy(
                            isReverting = false,
                            error = null,
                            selectedMessageForAction = null,
                            showRevertConfirmation = false,
                            revertMessageId = revertMessageId,
                            messages = filterMessagesForRevert(it.messages, revertMessageId),
                            inputText = nextInputText,
                            inputCursor = nextInputText.length
                        )
                    }
                    OcMobileLog.d(TAG, "[REVERT] Reloading messages after successful revert...")
                    ensureLoadMessages(sessionId)
                }
                .onFailure { error ->
                    val duration = Clock.System.now().toEpochMilliseconds() - startTime
                    OcMobileLog.e(TAG, "[REVERT] <<< Revert FAILED in ${duration}ms: " +
                            "error=${error.message}, type=${error::class.simpleName}", error)
                    _uiState.update {
                        it.copy(
                            isReverting = false,
                            error = ChatError.RevertFailed(error.message),
                            selectedMessageForAction = null,
                            showRevertConfirmation = false
                        )
                    }
                }
        }
    }

    /**
     * Fork a new session from the selected message.
     * Creates a new session with messages up to the selected point.
     */
    fun forkFromMessage() {
        if (_uiState.value.isReverting) return

        val sessionId = _uiState.value.currentSessionId
        val message = _uiState.value.selectedMessageForAction
        val messageIndex = message?.let { m -> _uiState.value.messages.indexOfFirst { it.id == m.id } }
        val totalMessages = _uiState.value.messages.size

        OcMobileLog.d(TAG, "[FORK] forkFromMessage called: sessionId=$sessionId, " +
                "messageId=${message?.id}, messageIndex=$messageIndex/$totalMessages")

        if (sessionId == null) {
            OcMobileLog.e(TAG, "[FORK] forkFromMessage ABORTED: sessionId is null")
            dismissMessageAction()
            return
        }
        if (message == null) {
            OcMobileLog.e(TAG, "[FORK] forkFromMessage ABORTED: selectedMessageForAction is null")
            dismissMessageAction()
            return
        }

        viewModelScope.launch {
            OcMobileLog.d(TAG, "[FORK] >>> Starting fork API call: sessionId=$sessionId, " +
                    "messageId=${message.id}, will copy messages up to index $messageIndex")
            _uiState.update { it.copy(isReverting = true, error = null, selectedMessageForAction = null) }

            val startTime = Clock.System.now().toEpochMilliseconds()
            sessionRepository.forkSession(sessionId, message.id)
                .onSuccess { newSession ->
                    val duration = Clock.System.now().toEpochMilliseconds() - startTime
                    OcMobileLog.d(TAG, "[FORK] <<< Fork SUCCESS in ${duration}ms: " +
                            "newSessionId=${newSession.id}, parentId=${newSession.parentId}, " +
                            "title=${newSession.title}")
                    _uiState.update {
                        it.copy(
                            currentSessionId = newSession.id,
                            revertMessageId = null,
                            messages = emptyList(),
                            lastGoodMessageId = null,
                            isLoading = true,
                            isReverting = false,
                            error = null
                        )
                    }
                    OcMobileLog.d(TAG, "[FORK] Loading messages for new forked session: ${newSession.id}")
                    ensureLoadMessages(newSession.id)
                }
                .onFailure { error ->
                    val duration = Clock.System.now().toEpochMilliseconds() - startTime
                    OcMobileLog.e(TAG, "[FORK] <<< Fork FAILED in ${duration}ms: " +
                            "error=${error.message}, type=${error::class.simpleName}", error)
                    _uiState.update {
                        it.copy(
                            isReverting = false,
                            error = ChatError.ForkFailed(error.message)
                        )
                    }
                }
        }
    }

    /**
     * Copy the message text to clipboard.
     * Returns the text that was copied for the caller to handle clipboard.
     */
    fun getMessageTextForCopy(): String? {
        val message = _uiState.value.selectedMessageForAction
        if (message == null) {
            OcMobileLog.w(TAG, "[COPY] getMessageTextForCopy: no message selected")
            return null
        }

        val textParts = message.parts
            .filterIsInstance<TextPart>()
            .filter { it.text.isNotEmpty() }
        val text = textParts.joinToString("\n") { it.text }
        OcMobileLog.d(TAG, "[COPY] getMessageTextForCopy: messageId=${message.id}, " +
                "textPartsCount=${textParts.size}, textLength=${text.length}")

        dismissMessageAction()
        return text
    }

    /**
     * Calculate how many messages would be reverted *after* the selected message.
     *
     * Note: OpenCode's revert semantics undo the selected message as well (the UI shows a marker at the revert
     * boundary). The caller may want to add 1 to include the selected message in user-facing counts.
     */
    fun getMessagesAfterSelectedCount(): Int {
        val targetMessageId = resolveRevertTargetMessageId(_uiState.value) ?: return 0
        val messages = _uiState.value.messages
        val selectedIndex = messages.indexOfFirst { it.id == targetMessageId }
        if (selectedIndex == -1) return 0
        return messages.size - selectedIndex - 1
    }

    // ==================== End Message Action Sheet Functions ====================

    /**
     * Abort the current session and stop any ongoing AI processing.
     * Used to cancel/interrupt a response that is being streamed.
     */
    fun abortSession() {
        val sessionId = _uiState.value.currentSessionId
        OcMobileLog.d(TAG, "[ABORT] abortSession called: sessionId=$sessionId, " +
                "isAborting=${_uiState.value.isAborting}, isSending=${_uiState.value.isSending}")

        if (sessionId == null) {
            OcMobileLog.w(TAG, "[ABORT] abortSession: No session ID, aborting")
            return
        }
        if (_uiState.value.isAborting) {
            OcMobileLog.w(TAG, "[ABORT] abortSession: Already aborting, ignoring")
            return
        }

        viewModelScope.launch {
            OcMobileLog.d(TAG, "[ABORT] >>> Starting abort API call: sessionId=$sessionId")
            _uiState.update { it.copy(isAborting = true, error = null) }

            sessionRepository.abortSession(sessionId)
                .onSuccess { success ->
                    OcMobileLog.d(TAG, "[ABORT] <<< Abort SUCCESS: success=$success")
                    _uiState.update {
                        it.copy(
                            isAborting = false,
                            isSending = false,
                            sessionStatus = SessionUiStatus.Idle
                        )
                    }
                    // Sync final state (partial/aborted message) from server.
                    ensureLoadMessages(sessionId)
                }
                .onFailure { error ->
                    OcMobileLog.e(TAG, "[ABORT] <<< Abort FAILED: ${error.message}", error)
                    _uiState.update {
                        it.copy(
                            isAborting = false,
                            error = ChatError.AbortFailed(error.message)
                        )
                    }
                }
        }
    }

    /**
     * Dismiss current error.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Retry loading after an error.
     */
    fun retry() {
        if (_uiState.value.isRefreshing) return
        refreshCommands()
        loadCurrentSession(isManualRefresh = true)
    }

    /**
     * Notify the shared chat logic that the app moved to the background.
     *
     * This timestamp is used by [onAppForegrounded] to decide whether to refresh.
     */
    fun onAppBackgrounded() {
        // Defensive: lifecycle callbacks can fire multiple times (or pass through intermediate states)
        // while the app is transitioning away from the foreground. Keep the first timestamp so the
        // computed background duration is stable.
        if (lastBackgroundedAtMs != null) return
        lastBackgroundedAtMs = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Notify the shared chat logic that the app returned to the foreground.
     *
     * We only refresh if the app was backgrounded for at least [FOREGROUND_REFRESH_MIN_BACKGROUND_MS],
     * to avoid unnecessary network calls during quick app switching.
     *
     * The refresh uses the existing "manual refresh" path (REST resync) but must not blank the chat UI.
     */
    fun onAppForegrounded() {
        val backgroundedAtMs = lastBackgroundedAtMs ?: return
        lastBackgroundedAtMs = null // Consume so we refresh at most once per background/foreground pair.

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val backgroundDurationMs = nowMs - backgroundedAtMs
        if (backgroundDurationMs < FOREGROUND_REFRESH_MIN_BACKGROUND_MS) return
        if (backgroundDurationMs < 0) return

        retry()
    }

    /**
     * Load a different session and its messages.
     * Called when user selects a session from a session picker.
     *
     * NOTE: Does NOT persist session ID - caller handles persistence.
     * This avoids duplicate persistence calls when the picker already persisted the selection.
     *
     * @param sessionId The session to load (already persisted by caller)
     */
    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            // NO sessionRepository.updateCurrentSessionId() - already done by caller

            // Update local state immediately
            clearStreamingBuffers()
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    revertMessageId = null,
                    messages = emptyList(), // Clear while loading
                    isLoading = true,
                    streamingMessageId = null,
                    lastGoodMessageId = null, // Clear for new session - will be set by loadMessages
                    error = null
                )
            }

            // Resolve revert pointer before loading messages (same reasoning as loadCurrentSession).
            val session = sessionRepository.getSession(sessionId).getOrNull()
            if (sessionId == _uiState.value.currentSessionId) {
                val revertMessageId = session?.revert?.messageId
                _uiState.update { state -> applyRevertPointer(state, revertMessageId) }
                viewModelScope.launch {
                    contextUsageRepository.updateUsage(_uiState.value.messages)
                }
            }

            // Load messages for new session
            ensureLoadMessages(sessionId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventStream.disconnect()
    }
}
