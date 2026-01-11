package com.ratulsarna.ocmobile.data.api

import com.ratulsarna.ocmobile.data.dto.*

/**
 * Interface for OpenCode HTTP API operations.
 */
interface OpenCodeApi {
    /**
     * Send a message to a session.
     */
    suspend fun sendMessage(
        sessionId: String,
        request: SendMessageRequest
    ): SendMessageResponse

    /**
     * Get messages in a session.
     *
     * @param limit Maximum number of messages to return (optional).
     * @param reverse If true, return newest-first (optional).
     */
    suspend fun getMessages(
        sessionId: String,
        limit: Int? = null,
        reverse: Boolean? = null
    ): List<MessageWithPartsDto>

    /**
     * Get a single session by ID.
     */
    suspend fun getSession(sessionId: String): SessionDto

    /**
     * Get all sessions.
     */
    suspend fun getSessions(
        search: String? = null,
        limit: Int? = null,
        start: Long? = null
    ): List<SessionDto>

    /**
     * Create a new session.
     */
    suspend fun createSession(request: CreateSessionRequest): SessionDto

    /**
     * Fork a session at a specific message.
     * Creates a new session with messages up to the specified point.
     */
    suspend fun forkSession(
        sessionId: String,
        request: ForkSessionRequest
    ): SessionDto

    /**
     * Revert a session to a specific message.
     * Removes all messages after the specified message in the same session.
     */
    suspend fun revertSession(
        sessionId: String,
        request: RevertSessionRequest
    ): SessionDto

    /**
     * Get all available agents.
     */
    suspend fun getAgents(): List<AgentDto>

    /**
     * Get all providers with their models.
     */
    suspend fun getProviders(): ProviderListResponse

    /**
     * Get the server configuration, including the default model.
     */
    suspend fun getConfig(): ConfigResponse

    /**
     * Get the current project info for a directory context.
     *
     * The server determines its instance/project context from:
     * - query `directory`, or
     * - header `x-opencode-directory`
     *
     * This method supports an optional explicit directory override which is applied via the
     * header (used for "add workspace by path" flows).
     */
    suspend fun getCurrentProject(directory: String? = null): ProjectInfoDto

    /**
     * Get server path information (home/state/config/worktree/directory).
     * (OpenCode: `GET /path`)
     */
    suspend fun getPath(): ServerPathDto

    /**
     * List all projects that have been opened with OpenCode on this installation.
     * (OpenCode: `GET /project`)
     */
    suspend fun listProjects(): List<ProjectInfoDto>

    /**
     * Read the content of a file by path relative to the project worktree.
     */
    suspend fun getFileContent(path: String): FileContentDto

    /**
     * List files and directories by path relative to the project worktree.
     */
    suspend fun listFiles(path: String): List<FileNodeDto>

    /**
     * Get git status information for the current project.
     */
    suspend fun getFileStatus(): List<FileStatusDto>

    /**
     * Abort an active session and stop any ongoing AI processing.
     * @param sessionId The session to abort
     * @return true if abort was successful
     */
    suspend fun abortSession(sessionId: String): Boolean

    /**
     * Find vault files matching a search query.
     * Calls the server's fuzzy file search endpoint.
     *
     * @param query Search term for fuzzy file matching
     * @param includeDirs If true, include directory matches as well (server default is true)
     * @return List of file paths relative to vault root
     */
    suspend fun findVaultFiles(query: String, includeDirs: Boolean = true): List<String>

    /**
     * Get pending permission prompts.
     * (OpenCode v1.1.1+: `GET /permission`)
     */
    suspend fun getPermissionRequests(): List<PermissionRequestDto>

    /**
     * Reply to a permission prompt.
     * (OpenCode v1.1.1+: `POST /permission/:requestID/reply`)
     */
    suspend fun replyToPermissionRequest(
        requestId: String,
        request: PermissionReplyRequestDto
    )

    /**
     * List all available slash commands.
     * (OpenCode: `GET /command`)
     */
    suspend fun listCommands(): List<CommandInfoDto>

    /**
     * Execute a slash command within a session.
     * (OpenCode: `POST /session/:sessionID/command`)
     */
    suspend fun sendCommand(
        sessionId: String,
        request: SendCommandRequest
    ): SendMessageResponse
}
