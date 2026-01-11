package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.data.api.ApiConfig
import com.ratulsarna.ocmobile.data.api.createOpenCodeHttpClient
import com.ratulsarna.ocmobile.data.dto.ConfigResponse
import com.ratulsarna.ocmobile.data.dto.MessagePartRequest
import com.ratulsarna.ocmobile.data.dto.ModelRequest
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.dto.CreateSessionRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Result of sending a message from the Share Extension.
 * Swift can check isSuccess and errorMessage.
 */
class ShareExtensionSendResult(
    val isSuccess: Boolean,
    val errorMessage: String?
) {
    companion object {
        fun success() = ShareExtensionSendResult(true, null)
        fun failure(message: String) = ShareExtensionSendResult(false, message)
    }
}

/**
 * Top-level function for Swift interop.
 * Swift calls this as: ShareExtensionSenderKt.sendFromShareExtension(...)
 *
 * @param text Optional text message
 * @param fileDataUrls List of data URLs (data:mime;base64,...)
 * @param filenames List of filenames corresponding to fileDataUrls
 * @param mimeTypes List of MIME types corresponding to fileDataUrls
 * @return ShareExtensionSendResult with success/failure status
 */
fun sendFromShareExtension(
    text: String?,
    fileDataUrls: List<String>,
    filenames: List<String>,
    mimeTypes: List<String>
): ShareExtensionSendResult {
    return runBlocking {
        try {
            val httpClient = createOpenCodeHttpClient()

            try {
                // 1. Pick a session to send into.
                // Prefer the most recently updated session; if none exist, create one.
                val sessions: List<SessionDto> = httpClient.get("${ApiConfig.OPENCODE_API_BASE_URL}/session").body()
                val sessionId = sessions.maxByOrNull { it.time.updated }?.id
                    ?: run {
                        val created: SessionDto = httpClient.post("${ApiConfig.OPENCODE_API_BASE_URL}/session") {
                            contentType(ContentType.Application.Json)
                            setBody(CreateSessionRequest(title = "Shared from iOS"))
                        }.body()
                        created.id
                    }

                // 2. Get default model from config
                val model: ModelRequest? = try {
                    val config: ConfigResponse = httpClient.get("${ApiConfig.OPENCODE_API_BASE_URL}/config").body()
                    config.model?.let { modelString ->
                        // Format is "providerId/modelId"
                        val parts = modelString.split("/", limit = 2)
                        if (parts.size == 2) {
                            ModelRequest(providerId = parts[0], modelId = parts[1])
                        } else null
                    }
                } catch (e: Exception) {
                    null // Use server default if config fetch fails
                }

                // 3. Build message parts
                val parts = mutableListOf<MessagePartRequest>()

                // Add text part if present
                text?.takeIf { it.isNotBlank() }?.let {
                    parts.add(MessagePartRequest(type = "text", text = it))
                }

                // Add file parts
                fileDataUrls.forEachIndexed { index, dataUrl ->
                    parts.add(
                        MessagePartRequest(
                            type = "file",
                            url = dataUrl,
                            filename = filenames.getOrNull(index),
                            mime = mimeTypes.getOrNull(index)
                        )
                    )
                }

                // 4. Send message to OpenCode API
                // Use timeout: if server responds quickly, check status.
                // If timeout (AI still processing), assume success - the message was sent.
                // The AI response will arrive via SSE to the main app.
                val request = SendMessageRequest(
                    parts = parts,
                    model = model,
                    agent = null
                )

                // 5 second timeout: balances UX (quick dismissal) with reliability.
                // If timeout occurs, the HTTP request was already sent - the server is just
                // taking time to process the AI response. We return success and let the
                // main app receive the response via SSE. Longer timeouts hurt UX.
                try {
                    val response = withTimeout(5000) {
                        httpClient.post("${ApiConfig.OPENCODE_API_BASE_URL}/session/$sessionId/message") {
                            contentType(ContentType.Application.Json)
                            setBody(request)
                        }
                    }

                    if (response.status.isSuccess()) {
                        ShareExtensionSendResult.success()
                    } else {
                        ShareExtensionSendResult.failure("HTTP ${response.status.value}")
                    }
                } catch (e: TimeoutCancellationException) {
                    // Timeout means server accepted connection but AI is still processing.
                    // The message was sent successfully - return success.
                    ShareExtensionSendResult.success()
                }
            } finally {
                httpClient.close()
            }
        } catch (e: Exception) {
            ShareExtensionSendResult.failure(e.message ?: "Unknown error")
        }
    }
}
