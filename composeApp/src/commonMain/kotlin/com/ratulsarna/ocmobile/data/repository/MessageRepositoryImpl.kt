package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.*
import com.ratulsarna.ocmobile.domain.error.*
import com.ratulsarna.ocmobile.domain.model.*
import com.ratulsarna.ocmobile.domain.repository.MessageRepository
import com.ratulsarna.ocmobile.domain.repository.ModelSpec
import com.ratulsarna.ocmobile.util.OcMobileLog
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

/**
 * Implementation of MessageRepository using OpenCode API.
 */
class MessageRepositoryImpl(
    private val api: OpenCodeApi
) : MessageRepository {

    private companion object {
        private const val TAG = "MessageRepo"
        // Cap initial payload to keep startup responsive on mobile networks.
        // Uses OpenCode API pagination semantics: reverse=true returns newest-first.
        const val INITIAL_LOAD_LIMIT = 50
    }

    override suspend fun listCommands(): Result<List<CommandInfo>> {
        return runCatching {
            api.listCommands()
                .map { dto ->
                    CommandInfo(
                        name = dto.name,
                        description = dto.description,
                        hints = dto.hints
                    )
                }
        }.recoverCatching { e ->
            OcMobileLog.e(TAG, "listCommands failed: type=${e::class.simpleName}, message=${e.message}", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        model: ModelSpec?,
        variant: String?,
        systemPrompt: String?,
        agent: String?
    ): Result<AssistantMessage> {
        return runCatching {
            val request = SendMessageRequest(
                parts = listOf(MessagePartRequest(type = "text", text = text)),
                model = model?.let { ModelRequest(it.providerId, it.modelId) },
                variant = variant,
                system = systemPrompt,
                agent = agent
            )
            val response = api.sendMessage(sessionId, request)
            response.toDomain()
        }.recoverCatching { e ->
            OcMobileLog.e(
                TAG,
                "sendMessage failed: sessionId=$sessionId, " +
                    "textLength=${text.length}, model=$model, agent=$agent, " +
                    "type=${e::class.simpleName}, message=${e.message}",
                e
            )
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun sendMessageWithAttachments(
        sessionId: String,
        text: String?,
        attachments: List<Attachment>,
        model: ModelSpec?,
        variant: String?,
        systemPrompt: String?,
        agent: String?
    ): Result<AssistantMessage> {
        return runCatching {
            // Build message parts on Default dispatcher to avoid blocking UI
            val parts = withContext(Dispatchers.Default) {
                buildList {
                    // Add text part if present
                    if (!text.isNullOrBlank()) {
                        add(MessagePartRequest(type = "text", text = text))
                    }

                    // Add file parts for each attachment (base64 encoding is CPU-intensive)
                    attachments.forEach { attachment ->
                        add(
                            MessagePartRequest(
                                type = "file",
                                mime = attachment.mimeType,
                                filename = attachment.filename,
                                url = attachment.toDataUrl()
                            )
                        )
                    }
                }
            }

            val request = SendMessageRequest(
                parts = parts,
                model = model?.let { ModelRequest(it.providerId, it.modelId) },
                variant = variant,
                system = systemPrompt,
                agent = agent
            )
            val response = api.sendMessage(sessionId, request)
            response.toDomain()
        }.recoverCatching { e ->
            OcMobileLog.e(
                TAG,
                "sendMessageWithAttachments failed: sessionId=$sessionId, " +
                    "textLength=${text?.length ?: 0}, attachments=${attachments.size}, model=$model, agent=$agent, " +
                    "type=${e::class.simpleName}, message=${e.message}",
                e
            )
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun sendCommand(
        sessionId: String,
        command: String,
        arguments: String,
        attachments: List<Attachment>,
        model: ModelSpec?,
        variant: String?,
        agent: String?
    ): Result<AssistantMessage> {
        return runCatching {
            val parts = withContext(Dispatchers.Default) {
                attachments.map { attachment ->
                    MessagePartRequest(
                        type = "file",
                        mime = attachment.mimeType,
                        filename = attachment.filename,
                        url = attachment.toDataUrl()
                    )
                }
            }

            val request = SendCommandRequest(
                command = command,
                arguments = arguments,
                agent = agent,
                model = model?.let { "${it.providerId}/${it.modelId}" },
                variant = variant,
                parts = parts.takeIf { it.isNotEmpty() }
            )

            val response = api.sendCommand(sessionId, request)
            response.toDomain()
        }.recoverCatching { e ->
            OcMobileLog.e(
                TAG,
                "sendCommand failed: sessionId=$sessionId, command=$command, argsLen=${arguments.length}, " +
                    "attachments=${attachments.size}, model=$model, agent=$agent, " +
                    "type=${e::class.simpleName}, message=${e.message}",
                e
            )
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun getMessages(sessionId: String): Result<List<Message>> {
        return runCatching {
            // Fetch newest N, then reverse locally for chronological rendering.
            val dtos = api.getMessages(sessionId, limit = INITIAL_LOAD_LIMIT, reverse = true)
            dtos.map { it.toDomain() }
                .sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id })
        }.recoverCatching { e ->
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun findVaultEntries(query: String): Result<List<VaultEntry>> {
        return runCatching {
            // Server default includes directories; we need to type results.
            // We'll do two calls: includeDirs=true (all), includeDirs=false (files only),
            // then mark everything else as DIRECTORY while preserving server ordering.
            coroutineScope {
                val allDeferred = async { api.findVaultFiles(query, includeDirs = true) }
                val filesDeferred = async { api.findVaultFiles(query, includeDirs = false) }

                val all = allDeferred.await()
                val files = filesDeferred.await()

                fun normalize(path: String): String = path.trim().trimEnd('/')

                val fileSet = files.asSequence()
                    .map(::normalize)
                    .filter { it.isNotBlank() }
                    .toSet()

                all.asSequence()
                    .map(::normalize)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .map { normalized ->
                        VaultEntry(
                            path = normalized,
                            type = if (fileSet.contains(normalized)) VaultEntryType.FILE else VaultEntryType.DIRECTORY
                        )
                    }
                    .toList()
            }
        }.recoverCatching { e ->
            OcMobileLog.e(TAG, "findVaultEntries failed: query=$query, type=${e::class.simpleName}, message=${e.message}", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }
}

/**
 * Extension functions for DTO to domain mapping.
 */
internal fun SendMessageResponse.toDomain(): AssistantMessage {
    return AssistantMessage(
        id = info.id,
        sessionId = info.sessionId,
        createdAt = Instant.fromEpochMilliseconds(info.time.created),
        completedAt = info.time.completed?.let { Instant.fromEpochMilliseconds(it) },
        parts = parts.map { it.toDomain() },
        error = info.error?.toDomain(),
        cost = info.cost,
        tokens = info.tokens?.toDomain(),
        finishReason = info.finish,
        providerId = info.providerId,
        modelId = info.modelId
    )
}

internal fun MessageWithPartsDto.toDomain(): Message {
    val partsDomain = parts.map { it.toDomain() }

    return when (info.role) {
        "user" -> UserMessage(
            id = info.id,
            sessionId = info.sessionId,
            createdAt = Instant.fromEpochMilliseconds(info.time.created),
            parts = partsDomain
        )
        "assistant" -> AssistantMessage(
            id = info.id,
            sessionId = info.sessionId,
            createdAt = Instant.fromEpochMilliseconds(info.time.created),
            completedAt = info.time.completed?.let { Instant.fromEpochMilliseconds(it) },
            parts = partsDomain,
            error = info.error?.toDomain(),
            cost = info.cost,
            tokens = info.tokens?.toDomain(),
            finishReason = info.finish,
            providerId = info.providerId,
            modelId = info.modelId
        )
        else -> throw IllegalArgumentException("Unknown message role: ${info.role}")
    }
}

internal fun PartDto.toDomain(): MessagePart {
    return when (type) {
        "text" -> TextPart(
            id = id,
            text = text ?: "",
            synthetic = synthetic ?: false
        )
        "reasoning" -> ReasoningPart(
            id = id,
            text = text ?: ""
        )
        "tool" -> ToolPart(
            id = id,
            callId = callId ?: "",
            tool = tool ?: "",
            state = mapToolState(state?.status),
            input = state?.input?.toString(),
            output = state?.output,
            error = state?.error,
            title = state?.title,
            metadata = state?.metadata?.toString(),
            time = state?.time?.let { ToolTime(start = it.start, end = it.end, compacted = it.compacted) },
            attachments = state?.attachments?.map { it.toDomain() } ?: emptyList()
        )
        "file" -> FilePart(
            id = id,
            mime = mime ?: "",
            filename = filename,
            url = url ?: ""
        )
        "step-start" -> StepStartPart(
            id = id,
            snapshot = snapshot
        )
        "step-finish" -> StepFinishPart(
            id = id,
            reason = reason ?: "",
            snapshot = snapshot,
            cost = cost,
            tokens = tokens?.toDomain()
        )
        "snapshot" -> SnapshotPart(
            id = id,
            snapshot = snapshot ?: ""
        )
        "patch" -> PatchPart(
            id = id,
            hash = hash ?: "",
            files = files ?: emptyList()
        )
        "agent" -> AgentPart(
            id = id,
            name = name ?: ""
        )
        "retry" -> RetryPart(
            id = id,
            attempt = attempt ?: 0,
            error = error?.toDomain()
        )
        "compaction" -> CompactionPart(
            id = id,
            auto = auto ?: false
        )
        else -> UnknownPart(
            id = id,
            type = type,
            rawData = toString()
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

internal fun TokenUsageDto.toDomain(): TokenUsage = TokenUsage(
    input = input,
    output = output,
    reasoning = reasoning,
    cacheRead = cache?.read ?: 0,
    cacheWrite = cache?.write ?: 0
)

/**
 * Extracts the error message from ErrorDto, supporting both formats:
 * - Legacy format: { "name": "...", "message": "..." }
 * - NamedError format: { "name": "...", "data": { "message": "..." } }
 */
private fun ErrorDto.extractMessage(): String? {
    OcMobileLog.d("ErrorDto", "extractMessage: name=$name, data=$data, data.class=${data?.let { it::class.simpleName }}, message=$message")
    // Try to extract from data.message (NamedError format)
    val dataMessage = (data as? JsonObject)?.let { obj ->
        OcMobileLog.d("ErrorDto", "data is JsonObject, keys=${obj.keys}")
        (obj["message"] as? JsonPrimitive)?.contentOrNull
    }
    OcMobileLog.d("ErrorDto", "dataMessage=$dataMessage, returning=${dataMessage ?: message}")
    // Fall back to top-level message (legacy format)
    return dataMessage ?: message
}

internal fun ErrorDto.toDomain(): ApiError {
    val errorMessage = extractMessage()
    return when (name) {
        "ProviderAuthError" -> ProviderAuthError(
            providerId = null,
            message = errorMessage
        )
        "MessageAbortedError" -> MessageAbortedError(
            message = errorMessage
        )
        "MessageOutputLengthError" -> MessageOutputLengthError(
            message = errorMessage
        )
        "NotFoundError" -> NotFoundError(
            message = errorMessage
        )
        else -> GenericApiError(
            errorName = name,
            message = errorMessage
        )
    }
}
