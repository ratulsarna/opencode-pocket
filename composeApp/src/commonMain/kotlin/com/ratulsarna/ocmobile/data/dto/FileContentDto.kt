package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileContentDto(
    val type: String,
    val content: String,
    val encoding: String? = null,
    val mimeType: String? = null
)
