package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable

/**
 * Response from GET /config endpoint.
 * Only includes the model field we need; other fields ignored by kotlinx.serialization.
 */
@Serializable
data class ConfigResponse(
    val model: String? = null
)
