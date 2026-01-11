package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileNodeDto(
    val name: String,
    val path: String,
    val absolute: String,
    val type: String,
    val ignored: Boolean
)
