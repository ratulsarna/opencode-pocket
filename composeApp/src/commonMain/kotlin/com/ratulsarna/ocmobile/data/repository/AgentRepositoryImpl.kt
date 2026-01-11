package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.AgentDto
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.error.NetworkError
import com.ratulsarna.ocmobile.domain.model.Agent
import com.ratulsarna.ocmobile.domain.model.AgentMode
import com.ratulsarna.ocmobile.domain.repository.AgentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow

/**
 * Implementation of AgentRepository using OpenCode API and AppSettings.
 */
class AgentRepositoryImpl(
    private val api: OpenCodeApi,
    private val appSettings: AppSettings
) : AgentRepository {

    override suspend fun getAgents(): Result<List<Agent>> {
        return runCatching {
            val agents = api.getAgents().map { it.toDomain() }
            initializeDefaultAgentIfNeeded(agents)
            agents
        }.recoverCatching { e ->
            throw NetworkError(message = e.message, cause = e)
        }
    }

    private suspend fun initializeDefaultAgentIfNeeded(agents: List<Agent>) {
        val selected = appSettings.getSelectedAgent().first()
        if (!selected.isNullOrBlank()) return

        val primary = agents.filter { it.mode == AgentMode.PRIMARY }
        if (primary.isEmpty()) return

        val default = primary.firstOrNull { it.name.equals("default", ignoreCase = true) }
            ?: primary.firstOrNull { it.builtIn }
            ?: primary.firstOrNull()
            ?: return

        appSettings.setSelectedAgent(default.name)
    }

    override fun getSelectedAgent(): Flow<String?> {
        return appSettings.getSelectedAgent()
    }

    override suspend fun setSelectedAgent(agentName: String?) {
        appSettings.setSelectedAgent(agentName)
    }
}

/**
 * Extension function to map AgentDto to domain model.
 */
internal fun AgentDto.toDomain(): Agent = Agent(
    name = name,
    description = description ?: "",
    mode = when (mode) {
        "primary" -> AgentMode.PRIMARY
        "subagent" -> AgentMode.SUBAGENT
        else -> AgentMode.SUBAGENT
    },
    builtIn = builtIn
)
