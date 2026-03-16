package com.ratulsarna.ocmobile.data.mock

import com.ratulsarna.ocmobile.data.dto.MessageWithPartsDto
import com.ratulsarna.ocmobile.data.dto.MessageInfoDto
import com.ratulsarna.ocmobile.data.dto.MessageTimeDto
import com.ratulsarna.ocmobile.data.dto.PartDto
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.dto.SessionRevertDto
import com.ratulsarna.ocmobile.data.dto.SessionTimeDto
import com.ratulsarna.ocmobile.data.dto.TokenUsageDto
import com.ratulsarna.ocmobile.data.dto.ToolStateDto
import com.ratulsarna.ocmobile.domain.model.Event
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

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
        val now = Clock.System.now().toEpochMilliseconds()
        val seedSession = SessionDto(
            id = SEED_SESSION_ID,
            directory = "/mock",
            title = "Remodex-style chat refresh",
            time = SessionTimeDto(created = now - 7_200_000L, updated = now - 180_000L),
            parentId = null
        )
        sessions[SEED_SESSION_ID] = seedSession
        messagesBySession[SEED_SESSION_ID] = buildSeedMessages(SEED_SESSION_ID, now)

        val archiveSessionId = "mock-session-archive"
        sessions[archiveSessionId] = SessionDto(
            id = archiveSessionId,
            directory = "/mock",
            title = "Earlier sandbox pass",
            time = SessionTimeDto(created = now - 172_800_000L, updated = now - 28_800_000L),
            parentId = null
        )
        messagesBySession[archiveSessionId] = buildArchiveMessages(archiveSessionId, now)
    }

    private fun buildSeedMessages(sessionId: String, now: Long): MutableList<MessageWithPartsDto> {
        val base = now - 5_400_000L
        val messages = mutableListOf<MessageWithPartsDto>()

        fun add(
            role: String,
            minutesAfterStart: Int,
            parts: List<PartDto>,
            providerId: String? = null,
            modelId: String? = null,
            tokens: TokenUsageDto? = null,
            finish: String? = null,
            cost: Double? = null
        ) {
            val created = base + minutesAfterStart * 60_000L
            messages += MessageWithPartsDto(
                info = MessageInfoDto(
                    id = generateId(),
                    sessionId = sessionId,
                    role = role,
                    time = MessageTimeDto(created = created, completed = created + 20_000L),
                    tokens = tokens,
                    finish = finish,
                    cost = cost,
                    providerId = providerId,
                    modelId = modelId
                ),
                parts = parts
            )
        }

        add(
            role = "user",
            minutesAfterStart = 0,
            parts = listOf(
                PartDto(
                    type = "text",
                    text = "I want the iOS chat screen to feel much closer to remodex. The toolbar should float over the timeline and the composer should feel like a single glass card."
                )
            )
        )

        add(
            role = "assistant",
            minutesAfterStart = 1,
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            tokens = TokenUsageDto(input = 812, output = 178, reasoning = 46),
            finish = "stop",
            cost = 0.013,
            parts = listOf(
                PartDto(
                    id = generateId(),
                    type = "text",
                    text = "That direction makes sense. I would split the work so SwiftUI owns the chrome and UIKit keeps the transcript rendering, scrolling, and modal presentation."
                )
            )
        )

        add(
            role = "user",
            minutesAfterStart = 3,
            parts = listOf(
                PartDto(
                    type = "text",
                    text = "Yes. Make the top area translucent, keep current actions, and bring the thinking control into the visible bottom row instead of hiding it under the plus menu."
                )
            )
        )

        add(
            role = "assistant",
            minutesAfterStart = 6,
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            tokens = TokenUsageDto(input = 1042, output = 286, reasoning = 122),
            finish = "stop",
            cost = 0.021,
            parts = listOf(
                PartDto(
                    id = generateId(),
                    type = "reasoning",
                    text = "A direct UIKit skinning pass would keep the current structural split and fight the transparent header. The lower-risk change is to let SwiftUI own the top and bottom bars while the existing collection view continues to render the message thread."
                ),
                PartDto(
                    id = generateId(),
                    type = "tool",
                    callId = "tool_01_shell",
                    tool = "read_files",
                    state = ToolStateDto(
                        status = "completed",
                        title = "Inspect chat UI entry points",
                        output = "Mapped SwiftUIChatUIKitView, ChatViewController, and ChatComposerView. Confirmed the current header/composer ownership split.",
                        input = JsonPrimitive("iosApp/iosApp/ChatUIKit")
                    )
                ),
                PartDto(
                    id = generateId(),
                    type = "text",
                    text = "I would rebuild the toolbar and composer in SwiftUI, keep message rendering in UIKit, and use a bridged UITextView for cursor-accurate input. That preserves the mention/slash-command behavior while making the surface feel much closer to remodex."
                ),
                PartDto(
                    id = generateId(),
                    type = "patch",
                    hash = "b91f7d1",
                    files = listOf(
                        "iosApp/iosApp/ChatUIKit/SwiftUIChatUIKitView.swift",
                        "iosApp/iosApp/ChatUIKit/ChatViewController.swift",
                        "iosApp/iosApp/ChatUIKit/ChatScreenChromeView.swift"
                    )
                )
            )
        )

        add(
            role = "user",
            minutesAfterStart = 9,
            parts = listOf(
                PartDto(
                    type = "text",
                    text = "Can you also make attachments easier to scan while typing? I want them to read more like compact chips above the input."
                )
            )
        )

        add(
            role = "assistant",
            minutesAfterStart = 12,
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            tokens = TokenUsageDto(input = 1170, output = 244, reasoning = 64),
            finish = "stop",
            cost = 0.017,
            parts = listOf(
                PartDto(
                    id = generateId(),
                    type = "text",
                    text = "Yes. I would place pending attachments in a horizontal chip row inside the composer card so they stay visually grouped with the draft instead of floating as a separate stack below the timeline."
                ),
                PartDto(
                    id = generateId(),
                    type = "file",
                    mime = "image/jpeg",
                    filename = "chat-composer-reference.jpg",
                    url = "file:///mock/worktree/ref/chat-composer-reference.jpg"
                )
            )
        )

        add(
            role = "user",
            minutesAfterStart = 16,
            parts = listOf(
                PartDto(
                    type = "text",
                    text = "Great. Use mock mode for validation too. I want the simulator to open directly into a filled conversation so we can judge spacing and scroll behavior quickly."
                )
            )
        )

        add(
            role = "assistant",
            minutesAfterStart = 19,
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            tokens = TokenUsageDto(input = 1430, output = 318, reasoning = 101),
            finish = "stop",
            cost = 0.024,
            parts = listOf(
                PartDto(
                    id = generateId(),
                    type = "reasoning",
                    text = "A seeded mock transcript is the fastest way to evaluate the new glass toolbar, composer height, and scroll-to-latest button. It also makes regressions obvious without needing a live OpenCode server during every UI pass."
                ),
                PartDto(
                    id = generateId(),
                    type = "tool",
                    callId = "tool_02_mock",
                    tool = "mock_server",
                    state = ToolStateDto(
                        status = "completed",
                        title = "Seed mock transcript",
                        output = "Bootstrapped a multi-turn session with reasoning, tool, file, and patch parts so the thread has enough density for UI validation.",
                        input = JsonPrimitive("mock-session-001")
                    )
                ),
                PartDto(
                    id = generateId(),
                    type = "text",
                    text = "Mock mode is now the right place to validate this. It gives us a realistic message stack, keeps the thinking selector visible, and lets us iterate on the shell without pairing each run."
                )
            )
        )

        return messages
    }

    private fun buildArchiveMessages(sessionId: String, now: Long): MutableList<MessageWithPartsDto> {
        val base = now - 176_400_000L
        return mutableListOf(
            MessageWithPartsDto(
                info = MessageInfoDto(
                    id = generateId(),
                    sessionId = sessionId,
                    role = "user",
                    time = MessageTimeDto(created = base, completed = base + 10_000L)
                ),
                parts = listOf(
                    PartDto(
                        type = "text",
                        text = "Can you summarize what changed in the mobile workspace setup?"
                    )
                )
            ),
            MessageWithPartsDto(
                info = MessageInfoDto(
                    id = generateId(),
                    sessionId = sessionId,
                    role = "assistant",
                    time = MessageTimeDto(created = base + 60_000L, completed = base + 90_000L),
                    providerId = "anthropic",
                    modelId = "claude-sonnet-4-20250514",
                    tokens = TokenUsageDto(input = 360, output = 112, reasoning = 24),
                    finish = "stop",
                    cost = 0.008
                ),
                parts = listOf(
                    PartDto(
                        id = generateId(),
                        type = "text",
                        text = "The workspace bootstrap now picks the active project automatically, refreshes session state on foreground, and keeps mock mode available for UI-only passes."
                    )
                )
            )
        )
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

    suspend fun getSessions(search: String?, limit: Int?, start: Long?, directory: String?): List<SessionDto> = mutex.withLock {
        val filtered = sessions.values.asSequence()
            .filter { dto ->
                if (directory.isNullOrBlank()) return@filter true
                dto.directory == directory
            }
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
