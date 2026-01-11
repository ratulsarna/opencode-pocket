package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.ContextUsage
import com.ratulsarna.ocmobile.domain.model.Message
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for tracking context window usage in the current session.
 */
interface ContextUsageRepository {
    /**
     * Flow of current context usage, updated as messages change.
     */
    val contextUsage: StateFlow<ContextUsage>

    /**
     * Update context usage based on messages.
     * Uses the last assistant message's providerId/modelId to find context limit.
     */
    suspend fun updateUsage(messages: List<Message>)
}
