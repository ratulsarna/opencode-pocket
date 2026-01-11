package com.ratulsarna.ocmobile.data.mock

import com.ratulsarna.ocmobile.data.dto.MessageWithPartsDto
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.dto.SessionRevertDto
import com.ratulsarna.ocmobile.data.dto.SessionTimeDto
import com.ratulsarna.ocmobile.domain.model.Event
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared in-memory state for all mock implementations.
 * Thread-safe via Mutex. Stores DTOs, not domain models.
 */
class MockState {
    // Thread-safety: all mutable state protected by Mutex
    private val mutex = Mutex()

    // Store DTOs, not domain models
    private val sessions = mutableMapOf<String, SessionDto>()
    private val messagesBySession = mutableMapOf<String, MutableList<MessageWithPartsDto>>()
    private var currentSessionId: String = SEED_SESSION_ID

    // Event bus for SSE simulation
    private val eventBus = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    // Session ID stream - StateFlow has replay=1 by default, seeded with initial value
    private val sessionIdFlow = MutableStateFlow(currentSessionId)

    // KMP-compatible ID generation (no UUID.randomUUID() in commonMain)
    private var idCounter = 0L

    fun generateId(): String {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        // Ensure lexicographic ordering within the same millisecond (string comparisons).
        // This matters for revert filtering which relies on sortable IDs.
        val counter = (idCounter++).toString().padStart(19, '0')
        return "mock-$timestamp-$counter"
    }

    init {
        // SEED: Initialize with default session so app isn't empty on launch
        val now = Clock.System.now().toEpochMilliseconds()
        val seedSession = SessionDto(
            id = SEED_SESSION_ID,
            directory = "/mock",
            title = "Mock Session",
            time = SessionTimeDto(created = now, updated = now),
            parentId = null
        )
        sessions[SEED_SESSION_ID] = seedSession
        messagesBySession[SEED_SESSION_ID] = mutableListOf()
    }

    // Session CRUD methods - all use mutex for thread-safety
    suspend fun createSession(title: String?, parentId: String?): SessionDto = mutex.withLock {
        val id = generateId()
        val now = Clock.System.now().toEpochMilliseconds()
        val session = SessionDto(
            id = id,
            directory = "/mock",
            title = title ?: "Session ${id.takeLast(4)}",
            time = SessionTimeDto(created = now, updated = now),
            parentId = parentId
        )
        sessions[id] = session
        messagesBySession[id] = mutableListOf()
        session
    }

    suspend fun getSession(id: String): SessionDto? = mutex.withLock {
        sessions[id]
    }

    suspend fun getSessions(search: String?, limit: Int?, start: Long?): List<SessionDto> = mutex.withLock {
        val filtered = sessions.values.asSequence()
            .filter { dto ->
                if (start == null) return@filter true
                dto.time.updated >= start
            }
            .filter { dto ->
                if (search.isNullOrBlank()) return@filter true
                (dto.title ?: "").contains(search, ignoreCase = true)
            }
            .sortedByDescending { it.time.updated }
            .toList()

        if (limit != null) filtered.take(limit) else filtered
    }

    // Message CRUD methods
    suspend fun addMessage(sessionId: String, message: MessageWithPartsDto) = mutex.withLock {
        messagesBySession.getOrPut(sessionId) { mutableListOf() }.add(message)
        // Update session's updated time
        sessions[sessionId]?.let { session ->
            sessions[sessionId] = session.copy(
                time = session.time.copy(updated = Clock.System.now().toEpochMilliseconds())
            )
        }
    }

    suspend fun getMessages(sessionId: String): List<MessageWithPartsDto> = mutex.withLock {
        messagesBySession[sessionId]?.toList() ?: emptyList()
    }

    /**
     * Set the session's revert pointer (mirrors OpenCode semantics: messages are hidden, not immediately deleted).
     */
    suspend fun setRevert(sessionId: String, messageId: String, partId: String? = null) = mutex.withLock {
        sessions[sessionId]?.let { session ->
            sessions[sessionId] = session.copy(
                revert = SessionRevertDto(messageId = messageId, partId = partId),
                time = session.time.copy(updated = Clock.System.now().toEpochMilliseconds())
            )
        }
    }

    suspend fun clearRevert(sessionId: String) = mutex.withLock {
        sessions[sessionId]?.let { session ->
            sessions[sessionId] = session.copy(
                revert = null,
                time = session.time.copy(updated = Clock.System.now().toEpochMilliseconds())
            )
        }
    }

    /**
     * Commit an active revert by physically removing reverted messages, mirroring server cleanup behavior.
     *
     * OpenCode hides messages at/after [SessionRevertDto.messageId] and clears them later on resume.
     * This keeps mock mode aligned with real UX once the user sends a new message.
     */
    suspend fun commitRevert(sessionId: String) = mutex.withLock {
        val session = sessions[sessionId] ?: return@withLock
        val revertMessageId = session.revert?.messageId ?: return@withLock

        messagesBySession[sessionId]?.let { messages ->
            messagesBySession[sessionId] = messages
                .filter { it.info.id < revertMessageId }
                .toMutableList()
        }

        sessions[sessionId] = session.copy(
            revert = null,
            time = session.time.copy(updated = Clock.System.now().toEpochMilliseconds())
        )
    }

    // Session ID management
    suspend fun getCurrentSessionId(): String = mutex.withLock {
        currentSessionId
    }

    suspend fun setCurrentSessionId(id: String) {
        mutex.withLock {
            currentSessionId = id
        }
        // Emit outside lock to avoid potential deadlock
        sessionIdFlow.value = id
    }

    // Event emission (non-blocking)
    suspend fun emitEvent(event: Event) {
        eventBus.emit(event)
    }

    fun observeEvents(): Flow<Event> = eventBus

    fun observeSessionIds(): StateFlow<String> = sessionIdFlow

    companion object {
        const val SEED_SESSION_ID = "mock-session-001"
    }
}
