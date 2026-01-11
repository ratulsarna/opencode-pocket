package com.ratulsarna.ocmobile.domain.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Payload embedded in an `oc-pocket` pairing string.
 *
 * Pairing string format:
 *   oc-pocket-pair:v1:<base64url(json)>
 *
 * The JSON contains at least:
 *   { "version": 1, "baseUrl": "...", "token": "...", "name": "..." }
 */
@Serializable
data class PairingPayload(
    val version: Int,
    @SerialName("baseUrl")
    val baseUrl: String,
    val token: String,
    val name: String? = null
) {
    companion object {
        private const val PREFIX = "oc-pocket-pair:v1:"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        @OptIn(ExperimentalEncodingApi::class)
        fun decode(raw: String): Result<PairingPayload> {
            return runCatching {
                val trimmed = raw.trim()
                if (!trimmed.startsWith(PREFIX)) error("Invalid pairing string")

                val encoded = trimmed.removePrefix(PREFIX).trim()
                if (encoded.isBlank()) error("Invalid pairing string (missing payload)")

                val decodedBytes = Base64.decode(encoded.toBase64Standard())
                val decodedJson = decodedBytes.decodeToString()

                val parsed = json.decodeFromString<PairingPayload>(decodedJson)
                if (parsed.version != 1) error("Unsupported pairing version: ${parsed.version}")

                val normalizedBaseUrl = parsed.baseUrl.trim().trimEnd('/')
                val normalizedToken = parsed.token.trim()
                val normalizedName = parsed.name?.trim()?.takeIf { it.isNotBlank() }

                if (normalizedBaseUrl.isBlank()) error("Invalid pairing string (missing baseUrl)")
                if (normalizedToken.isBlank()) error("Invalid pairing string (missing token)")

                parsed.copy(
                    baseUrl = normalizedBaseUrl,
                    token = normalizedToken,
                    name = normalizedName
                )
            }
        }

        private fun String.toBase64Standard(): String {
            // Convert base64url -> base64 and restore padding.
            val normalized = this
                .replace('-', '+')
                .replace('_', '/')
            val pad = (4 - (normalized.length % 4)) % 4
            return normalized + "=".repeat(pad)
        }
    }
}
