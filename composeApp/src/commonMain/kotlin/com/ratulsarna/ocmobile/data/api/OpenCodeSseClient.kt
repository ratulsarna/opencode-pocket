package com.ratulsarna.ocmobile.data.api

import com.ratulsarna.ocmobile.data.dto.*
import com.ratulsarna.ocmobile.domain.model.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.ratulsarna.ocmobile.util.OcMobileLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val SSE_TAG = "OpenCodeSSE"
private const val MAPPER_TAG = "SseEventMapper"

/**
 * SSE client for subscribing to OpenCode events.
 */
class OpenCodeSseClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val eventMapper: SseEventMapper = SseEventMapper()
) {
    /**
     * Subscribe to the global event stream.
     * Returns a Flow that emits events as they arrive.
     */
    fun subscribeToEvents(
        onConnected: ((status: HttpStatusCode) -> Unit)? = null
    ): Flow<Event> = flow {
        val url = "$baseUrl/global/event"
        OcMobileLog.d(SSE_TAG) { "Connecting to $url" }
        try {
            httpClient.prepareGet(url) {
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.CacheControl, "no-cache")
            }.execute { response ->
                OcMobileLog.d(SSE_TAG) { "Connected, status: ${response.status}" }
                onConnected?.invoke(response.status)
                val channel = response.bodyAsChannel()

                var eventType: String? = null
                val data = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break  // null = EOF, exit loop

                    when {
                        line.startsWith("event:") -> {
                            eventType = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            data.append(line.removePrefix("data:").trim())
                        }
                        line.isEmpty() && data.isNotEmpty() -> {
                            // End of event - process it
                            // If no event: line was sent, extract type from payload.type in JSON
                            val effectiveEventType = eventType ?: eventMapper.extractEventType(data.toString())
                            if (effectiveEventType != null) {
                                val event = eventMapper.mapEvent(effectiveEventType, data.toString())
                                emit(event)
                            } else {
                                OcMobileLog.w(SSE_TAG, "Dropped event with no type: ${data.toString().take(100)}...")
                            }

                            // Reset for next event
                            eventType = null
                            data.clear()
                        }
                    }
                }
                OcMobileLog.d(SSE_TAG) { "Channel closed" }
            }
        } catch (e: Exception) {
            OcMobileLog.e(SSE_TAG) { "Connection error: ${e.message}" }
            throw e
        }
    }
}

/**
 * Maps raw SSE event data to domain Event objects.
 */
class SseEventMapper(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    /**
     * Extract event type from JSON payload when server doesn't send event: line.
     * The type is at payload.type in the JSON structure.
     */
    fun extractEventType(rawData: String): String? {
        return try {
            val jsonElement = json.parseToJsonElement(rawData)
            jsonElement.jsonObject["payload"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    fun mapEvent(eventType: String, rawData: String): Event {
        return try {
            val wrapper = json.decodeFromString<SseEventWrapper>(rawData)
            // The payload contains "type" and "properties" - we need to extract "properties"
            val payloadObj = wrapper.payload.jsonObject
            val properties = payloadObj["properties"]?.toString() ?: wrapper.payload.toString()
            OcMobileLog.d(MAPPER_TAG) { "eventType=$eventType" }
            mapPayload(eventType, wrapper.directory, properties)
        } catch (e: Exception) {
            OcMobileLog.e(MAPPER_TAG) { "Parse error for $eventType: ${e.message}" }
            UnknownEvent(
                directory = "",
                eventType = eventType,
                rawPayload = rawData
            )
        }
    }

    private fun mapPayload(eventType: String, directory: String, payload: String): Event {
        return when (eventType) {
            "message.part.updated" -> {
                // API structure: { "part": { "sessionID", "messageID", "type", ... }, "delta": "..." }
                val props = json.parseToJsonElement(payload).jsonObject
                val partObj = props["part"]?.jsonObject ?: return unknownEvent(directory, eventType, payload)
                val sessionId = partObj["sessionID"]?.jsonPrimitive?.content ?: return unknownEvent(directory, eventType, payload)
                val messageId = partObj["messageID"]?.jsonPrimitive?.content ?: return unknownEvent(directory, eventType, payload)
                val delta = props["delta"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val partDto = json.decodeFromString<PartDto>(partObj.toString())
                MessagePartUpdatedEvent(
                    directory = directory,
                    sessionId = sessionId,
                    messageId = messageId,
                    partIndex = 0, // Not provided in API, default to 0
                    part = mapPart(partDto),
                    delta = delta
                )
            }
            "message.removed" -> {
                val dto = json.decodeFromString<MessageRemovedPayload>(payload)
                MessageRemovedEvent(
                    directory = directory,
                    sessionId = dto.sessionId,
                    messageId = dto.messageId
                )
            }
            "message.updated" -> {
                val dto = json.decodeFromString<MessageUpdatedPayload>(payload)
                MessageUpdatedEvent(
                    directory = directory,
                    sessionId = dto.info.sessionId,
                    messageId = dto.info.id,
                    role = dto.info.role,
                    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(dto.info.time.created)
                )
            }
            "session.created" -> {
                val dto = json.decodeFromString<SessionEventPayload>(payload)
                SessionCreatedEvent(
                    directory = directory,
                    session = mapSession(dto.info)
                )
            }
            "session.updated" -> {
                val dto = json.decodeFromString<SessionEventPayload>(payload)
                SessionUpdatedEvent(
                    directory = directory,
                    session = mapSession(dto.info)
                )
            }
            "session.deleted" -> {
                val dto = json.decodeFromString<SessionEventPayload>(payload)
                SessionDeletedEvent(
                    directory = directory,
                    session = mapSession(dto.info)
                )
            }
            "session.status" -> {
                // API structure: { "sessionID": "...", "status": { "type": "idle"|"busy"|"retry"|"running"|"error" } }
                val props = json.parseToJsonElement(payload).jsonObject
                val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: return unknownEvent(directory, eventType, payload)
                val statusType = props["status"]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: "idle"
                val status = when (statusType) {
                    "idle" -> SessionStatus.IDLE
                    "busy" -> SessionStatus.RUNNING
                    "retry" -> SessionStatus.RUNNING
                    "running" -> SessionStatus.RUNNING
                    "error" -> SessionStatus.ERROR
                    else -> SessionStatus.IDLE
                }
                SessionStatusEvent(
                    directory = directory,
                    sessionId = sessionId,
                    status = status
                )
            }
            "session.idle" -> {
                // Separate event that signals session is idle (triggers reload)
                val props = json.parseToJsonElement(payload).jsonObject
                val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: return unknownEvent(directory, eventType, payload)
                SessionStatusEvent(
                    directory = directory,
                    sessionId = sessionId,
                    status = SessionStatus.IDLE
                )
            }
            "session.error" -> {
                // Some servers emit a dedicated event when the session errors.
                // Treat it the same as status=ERROR so the UI can refetch message state.
                val props = json.parseToJsonElement(payload).jsonObject
                val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: return unknownEvent(directory, eventType, payload)
                SessionStatusEvent(
                    directory = directory,
                    sessionId = sessionId,
                    status = SessionStatus.ERROR
                )
            }
            "file.watcher.updated" -> {
                val props = json.parseToJsonElement(payload).jsonObject
                val file = props["file"]?.jsonPrimitive?.content ?: return unknownEvent(directory, eventType, payload)
                val rawEvent = props["event"]?.jsonPrimitive?.content ?: "change"
                val updateType = when (rawEvent.lowercase()) {
                    "add", "added" -> FileWatcherUpdateType.ADD
                    "change", "changed" -> FileWatcherUpdateType.CHANGE
                    "unlink", "remove", "removed", "delete", "deleted" -> FileWatcherUpdateType.UNLINK
                    else -> FileWatcherUpdateType.CHANGE
                }
                FileWatcherUpdatedEvent(
                    directory = directory,
                    file = file,
                    event = updateType
                )
            }
            "permission.asked", "permission.updated" -> {
                // OpenCode v1.1.1+: permission.updated -> permission.asked (payload includes request id and patterns).
                val dto = json.decodeFromString<PermissionRequestDto>(payload)
                PermissionAskedEvent(
                    directory = directory,
                    requestId = dto.id,
                    sessionId = dto.sessionId,
                    permission = dto.permission,
                    patterns = dto.patterns,
                    always = dto.always,
                    toolMessageId = dto.tool?.messageId,
                    toolCallId = dto.tool?.callId
                )
            }
            "permission.replied" -> {
                val dto = json.decodeFromString<PermissionRepliedPayloadDto>(payload)
                PermissionRepliedEvent(
                    directory = directory,
                    sessionId = dto.sessionId,
                    requestId = dto.requestId,
                    reply = dto.reply
                )
            }
            // Intentionally unhandled events (informational, not needed for chat UI)
            "session.diff" -> unknownEvent(directory, eventType, payload, silent = true)
            else -> unknownEvent(directory, eventType, payload)
        }
    }

    private fun unknownEvent(directory: String, eventType: String, payload: String, silent: Boolean = false): UnknownEvent {
        if (!silent) {
            OcMobileLog.w(MAPPER_TAG, "Unhandled event: $eventType")
        }
        return UnknownEvent(
            directory = directory,
            eventType = eventType,
            rawPayload = payload
        )
    }

    private fun mapPart(dto: PartDto): MessagePart {
        return when (dto.type) {
            "text" -> TextPart(
                id = dto.id,
                text = dto.text ?: "",
                synthetic = dto.synthetic ?: false
            )
            "reasoning" -> ReasoningPart(
                id = dto.id,
                text = dto.text ?: ""
            )
            "tool" -> ToolPart(
                id = dto.id,
                callId = dto.callId ?: "",
                tool = dto.tool ?: "",
                state = mapToolState(dto.state?.status),
                input = dto.state?.input?.toString(),
                output = dto.state?.output,
                error = dto.state?.error,
                title = dto.state?.title,
                metadata = dto.state?.metadata?.toString(),
                time = dto.state?.time?.let { ToolTime(start = it.start, end = it.end, compacted = it.compacted) },
                attachments = dto.state?.attachments?.map { mapPart(it) } ?: emptyList()
            )
            "file" -> FilePart(
                id = dto.id,
                mime = dto.mime ?: "",
                filename = dto.filename,
                url = dto.url ?: ""
            )
            "step-start" -> StepStartPart(
                id = dto.id,
                snapshot = dto.snapshot
            )
            "step-finish" -> StepFinishPart(
                id = dto.id,
                reason = dto.reason ?: "",
                snapshot = dto.snapshot,
                cost = dto.cost,
                tokens = dto.tokens?.let { mapTokenUsage(it) }
            )
            "snapshot" -> SnapshotPart(
                id = dto.id,
                snapshot = dto.snapshot ?: ""
            )
            "patch" -> PatchPart(
                id = dto.id,
                hash = dto.hash ?: "",
                files = dto.files ?: emptyList()
            )
            "agent" -> AgentPart(
                id = dto.id,
                name = dto.name ?: ""
            )
            "retry" -> RetryPart(
                id = dto.id,
                attempt = dto.attempt ?: 0,
                error = dto.error?.let { mapError(it) }
            )
            "compaction" -> CompactionPart(
                id = dto.id,
                auto = dto.auto ?: false
            )
            else -> UnknownPart(
                id = dto.id,
                type = dto.type,
                rawData = dto.toString()
            )
        }
    }

    private fun mapToolState(state: String?): ToolState = when (state) {
        "pending" -> ToolState.PENDING
        "running" -> ToolState.RUNNING
        "completed" -> ToolState.COMPLETED
        "error" -> ToolState.ERROR
        else -> ToolState.PENDING
    }

    private fun mapStatus(status: SessionStatusDto): SessionStatus = when (status.type) {
        "idle" -> SessionStatus.IDLE
        "busy" -> SessionStatus.RUNNING
        "retry" -> SessionStatus.RUNNING  // Retry is a form of running/busy
        "running" -> SessionStatus.RUNNING
        "error" -> SessionStatus.ERROR
        else -> SessionStatus.IDLE
    }

    private fun mapSession(dto: SessionDto): Session {
        return Session(
            id = dto.id,
            directory = dto.directory,
            title = dto.title,
            createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(dto.time.created),
            updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(dto.time.updated),
            parentId = dto.parentId,
            revert = dto.revert?.let { revert ->
                com.ratulsarna.ocmobile.domain.model.SessionRevert(
                    messageId = revert.messageId,
                    partId = revert.partId,
                    diff = revert.diff
                )
            }
        )
    }

    private fun mapTokenUsage(dto: TokenUsageDto): TokenUsage {
        return TokenUsage(
            input = dto.input,
            output = dto.output,
            reasoning = dto.reasoning,
            cacheRead = dto.cache?.read ?: 0,
            cacheWrite = dto.cache?.write ?: 0
        )
    }

    private fun mapError(dto: ErrorDto): com.ratulsarna.ocmobile.domain.error.ApiError {
        return when (dto.name) {
            "ProviderAuthError" -> com.ratulsarna.ocmobile.domain.error.ProviderAuthError(
                providerId = null,
                message = dto.message
            )
            "MessageAbortedError" -> com.ratulsarna.ocmobile.domain.error.MessageAbortedError(
                message = dto.message
            )
            "MessageOutputLengthError" -> com.ratulsarna.ocmobile.domain.error.MessageOutputLengthError(
                message = dto.message
            )
            else -> com.ratulsarna.ocmobile.domain.error.GenericApiError(
                errorName = dto.name,
                message = dto.message
            )
        }
    }
}
