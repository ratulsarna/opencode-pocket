package com.ratulsarna.ocmobile.ui.screen.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionsViewModel(
    private val sessionRepository: SessionRepository,
    private val appSettings: AppSettings,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val searchLimit: Int = DEFAULT_SEARCH_LIMIT
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var loadRequestId: Long = 0L

    init {
        observeActiveSessionId()
        loadSessions(search = null)
    }

    /**
     * Called when Sessions screen becomes visible.
     * Refreshes data if not currently loading (avoids double-fetch when init load is in progress).
     */
    fun onScreenVisible() {
        val state = _uiState.value
        if (!state.isLoading && !state.isSearching) {
            refresh()
        }
    }

    fun refresh() {
        val query = _uiState.value.searchQuery.trim().ifBlank { null }
        // Cancel any pending debounced search; this refresh is explicit and should run immediately.
        searchJob?.cancel()
        searchJob = null
        loadSessions(search = query)
    }

    fun onSearchQueryChanged(query: String) {
        // SwiftUI's searchable field may call setters even when the value is unchanged
        // (e.g. focus/clear interactions). Avoid triggering a reload in that case.
        if (query == _uiState.value.searchQuery) return

        _uiState.update { it.copy(searchQuery = query) }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            val normalized = _uiState.value.searchQuery.trim()
            val effective = normalized.ifBlank { null }
            loadSessions(search = effective)
        }
    }

    fun createNewSession() {
        var shouldStart = false
        _uiState.update { state ->
            if (state.isCreatingSession) {
                state
            } else {
                shouldStart = true
                state.copy(isCreatingSession = true, error = null)
            }
        }
        if (!shouldStart) return

        viewModelScope.launch {
            sessionRepository.createSession(parentId = null)
                .onSuccess { session ->
                    _uiState.update {
                        it.copy(
                            isCreatingSession = false,
                            newSessionId = session.id
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCreatingSession = false,
                            error = error.message ?: "Failed to create session"
                        )
                    }
                }
        }
    }

    fun activateSession(sessionId: String) {
        var shouldStart = false
        _uiState.update { state ->
            if (state.isActivating) {
                state
            } else {
                shouldStart = true
                state.copy(
                    isActivating = true,
                    activatingSessionId = sessionId,
                    activationError = null
                )
            }
        }
        if (!shouldStart) return

        viewModelScope.launch {
            sessionRepository.updateCurrentSessionId(sessionId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isActivating = false,
                            activatingSessionId = null,
                            activatedSessionId = sessionId
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isActivating = false,
                            activatingSessionId = null,
                            activationError = error.message ?: "Failed to activate session"
                        )
                    }
                }
        }
    }

    fun clearActivation() {
        _uiState.update {
            it.copy(
                isActivating = false,
                activatingSessionId = null,
                activatedSessionId = null,
                activationError = null
            )
        }
    }

    fun clearNewSession() {
        _uiState.update { it.copy(newSessionId = null) }
    }

    private fun loadSessions(search: String?) {
        val requestId = ++loadRequestId

        // Cancel any previous load so (a) we don't waste network calls and (b) late responses don't
        // overwrite results for the latest query.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val isSearch = !search.isNullOrBlank()
            _uiState.update { state ->
                state.copy(
                    isLoading = !isSearch,
                    isSearching = isSearch,
                    error = null
                )
            }

            val start = if (isSearch) null else (Clock.System.now().toEpochMilliseconds() - DEFAULT_RECENT_WINDOW_MS)
            val limit = if (isSearch) searchLimit else null

            val result = if (isSearch && !search.isNullOrBlank()) {
                // We only display root sessions (parentId == null). When server-side search is
                // limited, we may receive many child sessions first, leaving 0 root sessions to show.
                // Retry with a larger limit so root sessions remain reachable.
                sessionRepository
                    .getSessions(search = search, limit = searchLimit, start = null)
                    .mapCatching { sessions ->
                        val visible = visibleSessions(sessions)
                        if (visible.size >= searchLimit || sessions.size < searchLimit) {
                            visible.take(searchLimit)
                        } else {
                            val expanded = sessionRepository
                                .getSessions(search = search, limit = DEFAULT_SEARCH_EXPANDED_LIMIT, start = null)
                                .getOrThrow()
                            visibleSessions(expanded).take(searchLimit)
                        }
                    }
            } else {
                sessionRepository.getSessions(search = search, limit = limit, start = start)
                    .map { sessions -> visibleSessions(sessions) }
            }

            result
                .onSuccess { sessions ->
                    if (requestId == loadRequestId) {
                        _uiState.update {
                            it.copy(
                                sessions = sessions,
                                isLoading = false,
                                isSearching = false,
                                error = null
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (requestId == loadRequestId) {
                        val message = if (isSearch) {
                            error.message ?: "Failed to search sessions"
                        } else {
                            error.message ?: "Failed to load sessions"
                        }
                        _uiState.update { state ->
                            // Preserve already-loaded sessions so transient failures don't blank the list.
                            val nextSessions = if (state.sessions.isEmpty()) emptyList() else state.sessions
                            state.copy(
                                sessions = nextSessions,
                                isLoading = false,
                                isSearching = false,
                                error = message
                            )
                        }
                    }
                }
        }
    }

    private fun visibleSessions(sessions: List<Session>): List<Session> =
        sessions
            .asSequence()
            .filter { it.parentId == null }
            .sortedByDescending { it.updatedAt }
            .toList()

    private fun observeActiveSessionId() {
        viewModelScope.launch {
            appSettings.getCurrentSessionId().collect { id ->
                _uiState.update { it.copy(activeSessionId = id) }
            }
        }
    }

    companion object {
        private const val DEFAULT_DEBOUNCE_MS = 150L
        private const val DEFAULT_SEARCH_LIMIT = 30
        private const val DEFAULT_SEARCH_EXPANDED_LIMIT = 200
        private const val DEFAULT_RECENT_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

data class SessionsUiState(
    val sessions: List<Session> = emptyList(),
    val searchQuery: String = "",
    val activeSessionId: String? = null,
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val isCreatingSession: Boolean = false,
    val isActivating: Boolean = false,
    val activatingSessionId: String? = null,
    val activatedSessionId: String? = null,
    val newSessionId: String? = null,
    val error: String? = null,
    val activationError: String? = null
)
