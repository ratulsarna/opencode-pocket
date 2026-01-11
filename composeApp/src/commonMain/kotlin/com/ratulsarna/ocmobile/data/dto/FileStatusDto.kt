package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileStatusDto(
    val path: String,
    val status: String,
    val added: Int? = null,
    val removed: Int? = null
)
