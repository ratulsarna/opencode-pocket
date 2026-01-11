package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.Session

/**
 * Repository for session management operations.
 */
interface SessionRepository {
    /**
     * Get the current active session ID.
     * In production, this is persisted locally on-device.
     */
    suspend fun getCurrentSessionId(): Result<String>

    /**
     * Get session details by ID.
     */
    suspend fun getSession(sessionId: String): Result<Session>

    /**
     * Get all available sessions.
     */
    suspend fun getSessions(
        search: String? = null,
        limit: Int? = null,
        start: Long? = null
    ): Result<List<Session>>

    /**
     * Create a new session.
     * Automatically updates the current session ID to the new session.
     *
     * @param title Optional title for the session
     * @param parentId Optional parent session ID (null creates a root session)
     */
    suspend fun createSession(title: String? = null, parentId: String? = null): Result<Session>

    /**
     * Fork a session, optionally from a specific message.
     * Used for error recovery - fork at the last known good message.
     * Automatically updates the current session ID to the new forked session.
     *
     * @param sessionId The session to fork from
     * @param messageId Optional message ID to fork at (copies history up to this point)
     * @return The newly created session
     */
    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session>

    /**
     * Revert a session to a specific message.
     * Removes all messages after the specified message in the same session.
     * Unlike fork, this modifies the existing session rather than creating a new one.
     *
     * @param sessionId The session to revert
     * @param messageId The message ID to revert to (messages after this will be removed)
     * @return The updated session
     */
    suspend fun revertSession(sessionId: String, messageId: String): Result<Session>

    /**
     * Update the current session ID (e.g., after manual session switch).
     */
    suspend fun updateCurrentSessionId(sessionId: String): Result<Unit>

    /**
     * Abort an active session and stop any ongoing AI processing.
     * Used to cancel/interrupt a response that is being streamed.
     *
     * @param sessionId The session to abort
     * @return Result containing true if abort was successful
     */
    suspend fun abortSession(sessionId: String): Result<Boolean>
}
