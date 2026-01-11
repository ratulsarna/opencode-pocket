package com.ratulsarna.ocmobile.domain.model

/**
 * Represents the current context window usage for a session.
 *
 * @property usedTokens Total tokens used in the context window
 * @property maxTokens Maximum context window size for the model (null if unknown)
 * @property percentage Usage percentage from 0.0 to 1.0 (null if max unknown)
 */
data class ContextUsage(
    val usedTokens: Int,
    val maxTokens: Int?,
    val percentage: Float?
) {
    companion object {
        val UNKNOWN = ContextUsage(usedTokens = 0, maxTokens = null, percentage = null)
    }
}
