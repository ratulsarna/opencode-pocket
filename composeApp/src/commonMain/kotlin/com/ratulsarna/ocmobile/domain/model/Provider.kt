package com.ratulsarna.ocmobile.domain.model

/**
 * Domain model representing an AI provider with its available models.
 */
data class Provider(
    val id: String,
    val name: String,
    val models: List<Model>
)
