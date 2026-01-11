package com.ratulsarna.ocmobile.data.mock

import com.ratulsarna.ocmobile.data.dto.SendMessageRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageResponse
import com.ratulsarna.ocmobile.domain.model.MessagePartUpdatedEvent
import com.ratulsarna.ocmobile.domain.model.SessionStatus
import com.ratulsarna.ocmobile.domain.model.SessionStatusEvent
import com.ratulsarna.ocmobile.domain.model.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Generates simulated assistant responses with background streaming.
 *
 * Key behavior:
 * - Returns response immediately (sendMessage() doesn't block)
 * - Streaming events are emitted in a background coroutine
 * - This matches real API behavior where HTTP returns quickly and streaming happens via SSE
 */
class MockResponseGenerator(
    private val state: MockState,
    private val scope: CoroutineScope,
    private val typingDelayMs: Long = 50L
) {
    private var responseIndex = 0

    /**
     * Generate a response for the given request.
     * Returns immediately - streaming happens in background.
     */
    fun generateResponse(
        sessionId: String,
        request: SendMessageRequest
    ): SendMessageResponse {
        // Generate IDs upfront (used in both storage and events)
        val assistantMessageId = state.generateId()
        val partId = state.generateId()

        // Select response (round-robin through templates)
        val responseText = MockData.RESPONSE_TEMPLATES[responseIndex % MockData.RESPONSE_TEMPLATES.size]
        responseIndex++

        // Launch streaming in background - sendMessage() returns immediately
        scope.launch {
            try {
                // 1. Emit RUNNING status
                state.emitEvent(
                    SessionStatusEvent(
                        directory = "/mock",
                        sessionId = sessionId,
                        status = SessionStatus.RUNNING
                    )
                )

                // 2. Stream text deltas word by word
                val words = responseText.split(" ")
                var accumulated = ""
                for ((i, word) in words.withIndex()) {
                    accumulated += (if (i > 0) " " else "") + word
                    delay(typingDelayMs)

                    state.emitEvent(
                        MessagePartUpdatedEvent(
                            directory = "/mock",
                            sessionId = sessionId,
                            messageId = assistantMessageId,  // MUST match stored message
                            partIndex = 0,
                            part = TextPart(id = partId, text = accumulated),
                            delta = (if (i > 0) " " else "") + word
                        )
                    )
                }

                // 3. Emit IDLE status (triggers ChatViewModel.loadMessages)
                state.emitEvent(
                    SessionStatusEvent(
                        directory = "/mock",
                        sessionId = sessionId,
                        status = SessionStatus.IDLE
                    )
                )
            } catch (e: Exception) {
                // On error, emit ERROR status
                state.emitEvent(
                    SessionStatusEvent(
                        directory = "/mock",
                        sessionId = sessionId,
                        status = SessionStatus.ERROR
                    )
                )
            }
        }

        // 4. Return DTO immediately with final text
        return MockData.createSendMessageResponse(
            messageId = assistantMessageId,
            sessionId = sessionId,
            text = responseText
        )
    }
}
