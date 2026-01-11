package com.ratulsarna.ocmobile.domain.model

/**
 * Domain model representing an AI model available from a provider.
 */
data class Model(
    val id: String,
    val providerId: String,
    val name: String,
    val contextLimit: Int? = null,
    val reasoningCapable: Boolean = false,
    /**
     * Provider/model-specific variants (e.g. reasoning effort presets like "low"/"medium"/"high").
     */
    val variants: List<String> = emptyList()
)
