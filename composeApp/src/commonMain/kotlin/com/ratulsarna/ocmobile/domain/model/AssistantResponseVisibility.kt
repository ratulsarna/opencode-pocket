package com.ratulsarna.ocmobile.domain.model

import kotlinx.serialization.Serializable

enum class AssistantResponseVisibilityPreset {
    DEFAULT,
    TEXT_ONLY,
    TEXT_AND_THINKING,
    ALL,
    CUSTOM
}

@Serializable
data class AssistantResponsePartVisibility(
    val showReasoning: Boolean,
    val showTools: Boolean,
    val showPatches: Boolean,
    val showAgents: Boolean,
    val showRetries: Boolean,
    val showCompactions: Boolean,
    val showUnknowns: Boolean
) {
    companion object {
        fun textOnly(): AssistantResponsePartVisibility = AssistantResponsePartVisibility(
            showReasoning = false,
            showTools = false,
            showPatches = false,
            showAgents = false,
            showRetries = false,
            showCompactions = false,
            showUnknowns = false
        )

        fun textAndThinking(): AssistantResponsePartVisibility = AssistantResponsePartVisibility(
            showReasoning = true,
            showTools = false,
            showPatches = false,
            showAgents = false,
            showRetries = false,
            showCompactions = false,
            showUnknowns = false
        )

        fun all(): AssistantResponsePartVisibility = AssistantResponsePartVisibility(
            showReasoning = true,
            showTools = true,
            showPatches = true,
            showAgents = true,
            showRetries = true,
            showCompactions = true,
            showUnknowns = true
        )
    }
}

@Serializable
data class AssistantResponseVisibilitySettings(
    val preset: AssistantResponseVisibilityPreset = AssistantResponseVisibilityPreset.DEFAULT,
    val custom: AssistantResponsePartVisibility? = null
) {
    fun effective(): AssistantResponsePartVisibility {
        return when (preset) {
            AssistantResponseVisibilityPreset.DEFAULT -> AssistantResponsePartVisibility.all()
            AssistantResponseVisibilityPreset.TEXT_ONLY -> AssistantResponsePartVisibility.textOnly()
            AssistantResponseVisibilityPreset.TEXT_AND_THINKING -> AssistantResponsePartVisibility.textAndThinking()
            AssistantResponseVisibilityPreset.ALL -> AssistantResponsePartVisibility.all()
            AssistantResponseVisibilityPreset.CUSTOM -> custom ?: AssistantResponsePartVisibility.all()
        }
    }
}
