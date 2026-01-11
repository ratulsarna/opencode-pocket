package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.Agent
import kotlinx.coroutines.flow.Flow

/**
 * Repository for agent operations.
 */
interface AgentRepository {
    /**
     * Get all available agents from the server.
     */
    suspend fun getAgents(): Result<List<Agent>>

    /**
     * Get the currently selected agent name as a flow.
     * Emits null if no agent is selected (use server default).
     */
    fun getSelectedAgent(): Flow<String?>

    /**
     * Set the selected agent.
     * @param agentName The agent name to select, or null to clear selection
     */
    suspend fun setSelectedAgent(agentName: String?)
}
