package com.ratulsarna.ocmobile.data.mock

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.AgentDto
import com.ratulsarna.ocmobile.data.dto.ConfigResponse
import com.ratulsarna.ocmobile.data.dto.CreateSessionRequest
import com.ratulsarna.ocmobile.data.dto.ForkSessionRequest
import com.ratulsarna.ocmobile.data.dto.FileContentDto
import com.ratulsarna.ocmobile.data.dto.FileNodeDto
import com.ratulsarna.ocmobile.data.dto.FileStatusDto
import com.ratulsarna.ocmobile.data.dto.RevertSessionRequest
import com.ratulsarna.ocmobile.data.dto.MessageWithPartsDto
import com.ratulsarna.ocmobile.data.dto.ModelDto
import com.ratulsarna.ocmobile.data.dto.ProjectInfoDto
import com.ratulsarna.ocmobile.data.dto.ServerPathDto
import com.ratulsarna.ocmobile.data.dto.ProviderDto
import com.ratulsarna.ocmobile.data.dto.ProviderListResponse
import com.ratulsarna.ocmobile.data.dto.SendMessageRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageResponse
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.dto.CommandInfoDto
import com.ratulsarna.ocmobile.data.dto.SendCommandRequest

/**
 * Mock implementation of OpenCodeApi.
 * Returns DTOs, not domain models. Uses state.generateId() for KMP compatibility.
 */
class MockOpenCodeApi(
    private val state: MockState,
    private val responseGenerator: MockResponseGenerator
) : OpenCodeApi {

    private data class MockFileEntry(
        val name: String,
        val type: String,
        val ignored: Boolean
    )

    private val mockWorktree = "/mock/worktree"
    private val mockDirectoryEntries = mapOf(
        "." to listOf(
            MockFileEntry("src", "directory", false),
            MockFileEntry("README.md", "file", false),
            MockFileEntry("build", "directory", true)
        ),
        "src" to listOf(
            MockFileEntry("Main.kt", "file", false),
            MockFileEntry("ui", "directory", false)
        ),
        "src/ui" to listOf(
            MockFileEntry("Theme.kt", "file", false)
        )
    )

    override suspend fun sendMessage(
        sessionId: String,
        request: SendMessageRequest
    ): SendMessageResponse {
        // Mirror OpenCode semantics: sending a new message commits any prior revert state.
        state.commitRevert(sessionId)

        // 1. Extract user text from request.parts (NOT request.text - doesn't exist!)
        val userText = MockData.extractUserText(request)

        // 2. Store user message as DTO
        val userMessageDto = MockData.createUserMessageDto(
            id = state.generateId(),
            sessionId = sessionId,
            text = userText
        )
        state.addMessage(sessionId, userMessageDto)

        // 3. Generate assistant response (launches background streaming, returns immediately)
        val response = responseGenerator.generateResponse(sessionId, request)

        // 4. Store assistant message as DTO (use response.info fields)
        val assistantDto = MockData.createAssistantMessageDto(
            id = response.info.id,
            sessionId = sessionId,
            text = response.parts.firstOrNull { it.type == "text" }?.text ?: "",
            tokens = response.info.tokens
        )
        state.addMessage(sessionId, assistantDto)

        return response
    }

    override suspend fun listCommands(): List<CommandInfoDto> {
        // Mirror OpenCode defaults: /init and /review, plus room for user-defined commands.
        return listOf(
            CommandInfoDto(
                name = "init",
                description = "create/update AGENTS.md",
                hints = emptyList()
            ),
            CommandInfoDto(
                name = "review",
                description = "review changes [commit|branch|pr], defaults to uncommitted",
                hints = listOf("\$ARGUMENTS")
            )
        )
    }

    override suspend fun sendCommand(sessionId: String, request: SendCommandRequest): SendMessageResponse {
        // In mock mode, treat the command invocation as a normal message so streaming continues to work.
        // This is only for local previews/tests; real behavior is implemented server-side.
        val syntheticText = buildString {
            append("/")
            append(request.command)
            if (request.arguments.isNotBlank()) {
                append(" ")
                append(request.arguments)
            }
        }

        val messageRequest = SendMessageRequest(
            parts = buildList {
                add(com.ratulsarna.ocmobile.data.dto.MessagePartRequest(type = "text", text = syntheticText))
                request.parts?.forEach { part -> add(part) }
            }
        )

        return sendMessage(sessionId, messageRequest)
    }

    override suspend fun getMessages(
        sessionId: String,
        limit: Int?,
        reverse: Boolean?
    ): List<MessageWithPartsDto> {
        val all = state.getMessages(sessionId)
        val ordered = if (reverse == true) all.asReversed() else all
        return if (limit != null) ordered.take(limit) else ordered
    }

    override suspend fun getSession(sessionId: String): SessionDto =
        state.getSession(sessionId) ?: throw RuntimeException("Session not found: $sessionId")

    override suspend fun getSessions(search: String?, limit: Int?, start: Long?): List<SessionDto> =
        state.getSessions(search = search, limit = limit, start = start)

    override suspend fun createSession(request: CreateSessionRequest): SessionDto {
        return state.createSession(
            title = request.title,
            parentId = request.parentId
        )
    }

    override suspend fun forkSession(sessionId: String, request: ForkSessionRequest): SessionDto {
        val newSession = state.createSession(
            title = "Forked session",
            parentId = sessionId
        )

        // Copy messages up to messageId if specified
        val messages = state.getMessages(sessionId)
        val toCopy = if (request.messageId != null) {
            val idx = messages.indexOfFirst { it.info.id == request.messageId }
            if (idx >= 0) messages.take(idx + 1) else messages  // Handle not found gracefully
        } else {
            messages
        }
        toCopy.forEach { state.addMessage(newSession.id, it) }

        return newSession
    }

    override suspend fun revertSession(sessionId: String, request: RevertSessionRequest): SessionDto {
        // Mirror OpenCode semantics: set a "revert pointer" on the session.
        // Messages are hidden from the UI but not immediately deleted.
        state.setRevert(sessionId, messageId = request.messageId, partId = request.partId)
        return state.getSession(sessionId) ?: throw RuntimeException("Session not found: $sessionId")
    }

    override suspend fun getAgents(): List<AgentDto> = listOf(
        AgentDto(
            name = "default",
            description = "Default OpenCode agent",
            mode = "primary",
            builtIn = false
        ),
        AgentDto(
            name = "assistant",
            description = "General assistant agent",
            mode = "primary",
            builtIn = false
        ),
        AgentDto(
            name = "general",
            description = "General-purpose agent for coding tasks",
            mode = "subagent",
            builtIn = true
        ),
        AgentDto(
            name = "explore",
            description = "Fast agent specialized for exploring codebases",
            mode = "subagent",
            builtIn = true
        )
    )

    override suspend fun getProviders(): ProviderListResponse = ProviderListResponse(
        all = listOf(
            ProviderDto(
                id = "anthropic",
                name = "Anthropic",
                models = mapOf(
                    "claude-sonnet-4-20250514" to ModelDto(
                        id = "claude-sonnet-4-20250514",
                        name = "Claude Sonnet 4"
                    ),
                    "claude-3-5-sonnet-20241022" to ModelDto(
                        id = "claude-3-5-sonnet-20241022",
                        name = "Claude 3.5 Sonnet"
                    ),
                    "claude-3-5-haiku-20241022" to ModelDto(
                        id = "claude-3-5-haiku-20241022",
                        name = "Claude 3.5 Haiku"
                    )
                )
            ),
            ProviderDto(
                id = "openai",
                name = "OpenAI",
                models = mapOf(
                    "gpt-4o" to ModelDto(
                        id = "gpt-4o",
                        name = "GPT-4o"
                    ),
                    "gpt-4o-mini" to ModelDto(
                        id = "gpt-4o-mini",
                        name = "GPT-4o Mini"
                    )
                )
            ),
            ProviderDto(
                id = "google",
                name = "Google",
                models = mapOf(
                    "gemini-2.0-flash" to ModelDto(
                        id = "gemini-2.0-flash",
                        name = "Gemini 2.0 Flash"
                    )
                )
            )
        ),
        connected = listOf("anthropic", "openai")
    )

    override suspend fun getConfig(): ConfigResponse = ConfigResponse(
        model = "anthropic/claude-sonnet-4-20250514"
    )

    override suspend fun getCurrentProject(directory: String?): ProjectInfoDto = ProjectInfoDto(
        id = "mock-project",
        worktree = mockWorktree,
        vcs = "git",
        name = "Mock Project",
        sandboxes = listOf(mockWorktree)
    )

    override suspend fun getPath(): ServerPathDto = ServerPathDto(
        home = "/mock/home",
        state = "/mock/state",
        config = "/mock/config",
        worktree = mockWorktree,
        directory = mockWorktree
    )

    override suspend fun listProjects(): List<ProjectInfoDto> = listOf(getCurrentProject(null))

    override suspend fun getFileContent(path: String): FileContentDto {
        val content = if (path.endsWith(".json", ignoreCase = true)) {
            """{ "example": true, "path": "$path" }"""
        } else {
            "# Mock File\n\nThis is mock content for `$path`."
        }
        return FileContentDto(
            type = "text",
            content = content
        )
    }

    override suspend fun listFiles(path: String): List<FileNodeDto> {
        val normalized = path.trim().removePrefix("./").trimEnd('/')
        val key = if (normalized.isEmpty() || normalized == ".") "." else normalized
        val entries = mockDirectoryEntries[key].orEmpty()

        return entries.map { entry ->
            val childPath = if (key == ".") entry.name else "$key/${entry.name}"
            FileNodeDto(
                name = entry.name,
                path = childPath,
                absolute = "$mockWorktree/$childPath",
                type = entry.type,
                ignored = entry.ignored
            )
        }
    }

    override suspend fun getFileStatus(): List<FileStatusDto> {
        return listOf(
            FileStatusDto(path = "README.md", status = "modified"),
            FileStatusDto(path = "src/Main.kt", status = "A"),
            FileStatusDto(path = "old.txt", status = "deleted", added = 0, removed = 12)
        )
    }

    override suspend fun abortSession(sessionId: String): Boolean {
        // Mock implementation - always succeeds
        return true
    }

    override suspend fun findVaultFiles(query: String, includeDirs: Boolean): List<String> {
        // Mock vault files for testing
        val mockVaultEntries = listOf(
            "Notes/Projects/opencode-pocket.md",
            "Notes/Dated/2025-12-19.md",
            "Notes/Ideas/Feature-ideas.md",
            "Notes/Daily/2025-12-18.md",
            "Notes/Reference/Kotlin-tips.md",
            "Notes/Reference/Compose-patterns.md",
            // Directories often come back from the real endpoint too
            "Notes/",
            "Notes/Reference/"
        )
        // Simple case-insensitive filtering
        return if (query.isBlank()) {
            emptyList()
        } else {
            val filtered = mockVaultEntries.filter { it.contains(query, ignoreCase = true) }
            if (includeDirs) filtered else filtered.filterNot { it.endsWith('/') }
        }
    }

    override suspend fun getPermissionRequests(): List<com.ratulsarna.ocmobile.data.dto.PermissionRequestDto> {
        // Mock mode currently does not simulate permission prompts.
        return emptyList()
    }

    override suspend fun replyToPermissionRequest(
        requestId: String,
        request: com.ratulsarna.ocmobile.data.dto.PermissionReplyRequestDto
    ) {
        // No-op in mock mode.
    }
}
