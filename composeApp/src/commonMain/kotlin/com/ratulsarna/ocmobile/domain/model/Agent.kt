package com.ratulsarna.ocmobile.domain.model

/**
 * Represents an available agent for message processing.
 */
data class Agent(
    val name: String,
    val description: String,
    val mode: AgentMode,
    val builtIn: Boolean
)

/**
 * Agent execution mode.
 */
enum class AgentMode {
    /** Primary agent for main chat interactions */
    PRIMARY,
    /** Subagent for specialized tasks */
    SUBAGENT
}
