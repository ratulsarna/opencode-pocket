package com.ratulsarna.ocmobile.domain.model

data class FileTextContent(
    val path: String,
    val content: String,
    val mimeType: String? = null,
    val encoding: String? = null
)
