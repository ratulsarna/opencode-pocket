package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.PermissionReplyRequestDto
import com.ratulsarna.ocmobile.domain.error.NetworkError
import com.ratulsarna.ocmobile.domain.model.PermissionReply
import com.ratulsarna.ocmobile.domain.model.PermissionRequest
import com.ratulsarna.ocmobile.domain.repository.PermissionRepository
import com.ratulsarna.ocmobile.util.OcMobileLog

class PermissionRepositoryImpl(
    private val api: OpenCodeApi
) : PermissionRepository {
    private companion object {
        private const val TAG = "PermissionRepo"
    }

    override suspend fun getPendingRequests(): Result<List<PermissionRequest>> {
        return runCatching {
            api.getPermissionRequests().map { dto ->
                PermissionRequest(
                    requestId = dto.id,
                    sessionId = dto.sessionId,
                    permission = dto.permission,
                    patterns = dto.patterns,
                    always = dto.always,
                    toolMessageId = dto.tool?.messageId,
                    toolCallId = dto.tool?.callId
                )
            }
        }.recoverCatching { e ->
            OcMobileLog.e(TAG, "getPendingRequests failed: ${e.message}", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun reply(
        requestId: String,
        reply: PermissionReply,
        message: String?
    ): Result<Unit> {
        return runCatching {
            val replyWire = when (reply) {
                PermissionReply.ONCE -> "once"
                PermissionReply.ALWAYS -> "always"
                PermissionReply.REJECT -> "reject"
            }
            api.replyToPermissionRequest(
                requestId = requestId,
                request = PermissionReplyRequestDto(reply = replyWire, message = message)
            )
        }.recoverCatching { e ->
            OcMobileLog.e(TAG, "reply failed: requestId=$requestId reply=$reply messagePresent=${!message.isNullOrBlank()}", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }
}
