package com.ratulsarna.ocmobile.data.mock

import com.ratulsarna.ocmobile.data.dto.AssistantMessageInfoDto
import com.ratulsarna.ocmobile.data.dto.MessageInfoDto
import com.ratulsarna.ocmobile.data.dto.MessageTimeDto
import com.ratulsarna.ocmobile.data.dto.MessageWithPartsDto
import com.ratulsarna.ocmobile.data.dto.PartDto
import com.ratulsarna.ocmobile.data.dto.SendMessageRequest
import com.ratulsarna.ocmobile.data.dto.SendMessageResponse
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.dto.SessionTimeDto
import com.ratulsarna.ocmobile.data.dto.TokenUsageDto
import kotlin.time.Clock

/**
 * DTO factories, seed data, and response templates for mock implementations.
 * Note: ID generation is in MockState.generateId() for KMP compatibility.
 */
object MockData {
    // Response templates for simulated assistant responses
    val RESPONSE_TEMPLATES = listOf(
        "I understand your question. Let me help you with that.",
        "That's an interesting point. Here's my analysis of the situation.",
        "Based on my understanding, I would recommend the following approach.",
        "Let me break this down step by step for you.",
        "Great question! Here's what I think about this topic.",
        "I've analyzed your request and here are my thoughts on it.",
        "This is a common scenario. Here's how I would handle it.",
        "Let me provide you with a comprehensive response to that."
    )

    /**
     * Create a SessionDto with the correct time structure.
     */
    fun createSessionDto(id: String, title: String?, parentId: String?): SessionDto {
        val now = Clock.System.now().toEpochMilliseconds()
        return SessionDto(
            id = id,
            directory = "/mock",
            title = title ?: "Session ${id.takeLast(4)}",
            time = SessionTimeDto(created = now, updated = now),
            parentId = parentId
        )
    }

    /**
     * Create a user message DTO (role = "user").
     */
    fun createUserMessageDto(id: String, sessionId: String, text: String): MessageWithPartsDto {
        val now = Clock.System.now().toEpochMilliseconds()
        return MessageWithPartsDto(
            info = MessageInfoDto(
                id = id,
                sessionId = sessionId,
                role = "user",
                time = MessageTimeDto(created = now, completed = now)
            ),
            parts = listOf(PartDto(type = "text", text = text))
        )
    }

    /**
     * Create an assistant message DTO (role = "assistant").
     * Matches SendMessageResponse structure for consistency.
     */
    fun createAssistantMessageDto(
        id: String,
        sessionId: String,
        text: String,
        tokens: TokenUsageDto? = null
    ): MessageWithPartsDto {
        val now = Clock.System.now().toEpochMilliseconds()
        return MessageWithPartsDto(
            info = MessageInfoDto(
                id = id,
                sessionId = sessionId,
                role = "assistant",
                time = MessageTimeDto(created = now, completed = now),
                tokens = tokens,
                finish = "stop",
                cost = 0.001
            ),
            parts = listOf(PartDto(type = "text", text = text))
        )
    }

    /**
     * Create a SendMessageResponse with the correct (info, parts) structure.
     */
    fun createSendMessageResponse(
        messageId: String,
        sessionId: String,
        text: String
    ): SendMessageResponse {
        val now = Clock.System.now().toEpochMilliseconds()
        return SendMessageResponse(
            info = AssistantMessageInfoDto(
                id = messageId,
                sessionId = sessionId,
                role = "assistant",
                time = MessageTimeDto(created = now, completed = now),
                finish = "stop",
                cost = 0.001,
                tokens = TokenUsageDto(input = 50, output = text.length / 4)
            ),
            parts = listOf(PartDto(type = "text", text = text))
        )
    }

    /**
     * Extract user text from SendMessageRequest.parts.
     * SendMessageRequest has no text field - text is in parts.
     */
    fun extractUserText(request: SendMessageRequest): String {
        return request.parts
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
    }
}
