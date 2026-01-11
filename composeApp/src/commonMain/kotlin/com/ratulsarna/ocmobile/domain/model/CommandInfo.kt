package com.ratulsarna.ocmobile.domain.model

data class CommandInfo(
    val name: String,
    val description: String? = null,
    val hints: List<String> = emptyList()
)

