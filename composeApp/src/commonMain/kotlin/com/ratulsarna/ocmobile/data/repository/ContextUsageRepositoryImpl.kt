package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.domain.model.AssistantMessage
import com.ratulsarna.ocmobile.domain.model.ContextUsage
import com.ratulsarna.ocmobile.domain.model.Message
import com.ratulsarna.ocmobile.domain.model.Provider
import com.ratulsarna.ocmobile.domain.repository.ContextUsageRepository
import com.ratulsarna.ocmobile.domain.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of ContextUsageRepository.
 *
 * Calculates context window usage based on the last assistant message's token data
 * and the model's context limit. Matches OpenCode's calculation approach.
 */
class ContextUsageRepositoryImpl(
    private val modelRepository: ModelRepository
) : ContextUsageRepository {

    private val _contextUsage = MutableStateFlow(ContextUsage.UNKNOWN)
    override val contextUsage: StateFlow<ContextUsage> = _contextUsage.asStateFlow()

    /** Fallback for transient network failures - last successful provider fetch */
    private var lastKnownProviders: List<Provider> = emptyList()

    override suspend fun updateUsage(messages: List<Message>) {
        // Find LAST assistant message with output tokens (matching OpenCode)
        val last = messages.filterIsInstance<AssistantMessage>()
            .lastOrNull { it.tokens?.output ?: 0 > 0 }

        if (last?.tokens == null) {
            _contextUsage.value = ContextUsage.UNKNOWN
            return
        }

        // Total tokens = input + output + reasoning + cache (all contribute to context)
        val total = with(last.tokens!!) {
            input + output + reasoning + cacheRead + cacheWrite
        }

        // Get model context limit using the MESSAGE's model (not selected model)
        val contextLimit = getModelContextLimit(last.providerId, last.modelId)
        val percentage = contextLimit?.let { (total.toFloat() / it).coerceIn(0f, 1f) }

        _contextUsage.value = ContextUsage(
            usedTokens = total,
            maxTokens = contextLimit,
            percentage = percentage
        )
    }

    private suspend fun getModelContextLimit(providerId: String?, modelId: String?): Int? {
        if (providerId == null || modelId == null) return null

        val providers = modelRepository.getConnectedProviders()
            .onSuccess { lastKnownProviders = it }
            .getOrNull()
            ?: lastKnownProviders.takeIf { it.isNotEmpty() }
            ?: return null

        return providers
            .find { it.id == providerId }
            ?.models
            ?.find { it.id == modelId }
            ?.contextLimit
    }
}
