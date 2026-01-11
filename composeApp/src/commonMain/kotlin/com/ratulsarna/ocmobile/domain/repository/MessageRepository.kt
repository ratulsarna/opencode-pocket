package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.AssistantMessage
import com.ratulsarna.ocmobile.domain.model.Attachment
import com.ratulsarna.ocmobile.domain.model.CommandInfo
import com.ratulsarna.ocmobile.domain.model.Message
import com.ratulsarna.ocmobile.domain.model.VaultEntry

/**
 * Repository for message operations.
 */
interface MessageRepository {
    /**
     * List available slash commands (e.g. /init, /review).
     */
    suspend fun listCommands(): Result<List<CommandInfo>>

    /**
     * Send a text message to a session.
     *
     * @param sessionId The session to send to
     * @param text The message text
     * @param model Optional model override
     * @param systemPrompt Optional system prompt override
     * @param agent Optional agent to use for processing
     * @return The assistant's response
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        model: ModelSpec? = null,
        variant: String? = null,
        systemPrompt: String? = null,
        agent: String? = null
    ): Result<AssistantMessage>

    /**
     * Send a message with optional text and/or attachments to a session.
     *
     * @param sessionId The session to send to
     * @param text Optional message text (can be null if only sending attachments)
     * @param attachments List of file/image attachments to send
     * @param model Optional model override
     * @param systemPrompt Optional system prompt override
     * @param agent Optional agent to use for processing
     * @return The assistant's response
     */
    suspend fun sendMessageWithAttachments(
        sessionId: String,
        text: String?,
        attachments: List<Attachment>,
        model: ModelSpec? = null,
        variant: String? = null,
        systemPrompt: String? = null,
        agent: String? = null
    ): Result<AssistantMessage>

    /**
     * Execute a slash command within a session.
     *
     * Mirrors OpenCode TUI behavior: the client sends `command` and raw `arguments`
     * to the server, which expands and executes the underlying command template.
     */
    suspend fun sendCommand(
        sessionId: String,
        command: String,
        arguments: String,
        attachments: List<Attachment> = emptyList(),
        model: ModelSpec? = null,
        variant: String? = null,
        agent: String? = null
    ): Result<AssistantMessage>

    /**
     * Get all messages in a session.
     */
    suspend fun getMessages(sessionId: String): Result<List<Message>>

    /**
     * Find vault entries (files and directories) matching a search query.
     * Used for attaching server-side references to messages.
     *
     * @param query Search term for fuzzy file matching
     * @return List of typed vault entries relative to vault root
     */
    suspend fun findVaultEntries(query: String): Result<List<VaultEntry>>
}

/**
 * Model specification for message requests.
 */
data class ModelSpec(
    val providerId: String,
    val modelId: String
)
