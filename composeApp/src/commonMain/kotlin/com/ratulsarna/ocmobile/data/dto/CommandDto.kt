package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CommandInfoDto(
    val name: String,
    val description: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val mcp: Boolean? = null,
    /**
     * OpenCode returns a `template` field, which may be a string or an object depending on command type.
     * We treat it as untyped JSON so the client can safely deserialize.
     */
    val template: JsonElement? = null,
    val subtask: Boolean? = null,
    val hints: List<String> = emptyList()
)

@Serializable
data class SendCommandRequest(
    val command: String,
    val arguments: String,
    val agent: String? = null,
    /**
     * Model string in the form `providerID/modelID` (e.g. `openai/gpt-4o`).
     */
    val model: String? = null,
    val variant: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    /**
     * Optional attachment parts (OpenCode currently accepts file parts for command input).
     */
    val parts: List<MessagePartRequest>? = null
)

