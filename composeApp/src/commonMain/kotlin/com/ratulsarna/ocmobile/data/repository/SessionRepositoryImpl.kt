package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.CreateSessionRequest
import com.ratulsarna.ocmobile.data.dto.ForkSessionRequest
import com.ratulsarna.ocmobile.data.dto.RevertSessionRequest
import com.ratulsarna.ocmobile.data.dto.SessionDto
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.error.NetworkError
import com.ratulsarna.ocmobile.domain.error.NotFoundError
import com.ratulsarna.ocmobile.domain.error.UnauthorizedError
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.util.OcMobileLog
import kotlin.time.Clock
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Instant

private const val TAG = "SessionRepo"

/**
 * Implementation of SessionRepository using OpenCode API.
 *
 * The active session ID is stored locally in [AppSettings] so the app can switch sessions without a
 * separate session-id infrastructure service.
 */
class SessionRepositoryImpl(
    private val api: OpenCodeApi,
    private val appSettings: AppSettings
) : SessionRepository {

    override suspend fun getCurrentSessionId(): Result<String> {
        return runCatching {
            val stored = appSettings.getCurrentSessionIdSnapshot()
            if (!stored.isNullOrBlank()) {
                return@runCatching stored
            }

            // Fallback: choose most recently updated session from the server, or create one if none exist.
            // Prefer a small request; server returns sessions sorted by most recently updated.
            val sessions = api.getSessions(limit = 1).map { it.toDomain() }
            val mostRecent = sessions.firstOrNull()

            val resolvedId = mostRecent?.id
                ?: api.createSession(CreateSessionRequest(parentId = null, title = null)).toDomain().id

            appSettings.setCurrentSessionId(resolvedId)
            resolvedId
        }.recoverCatching { e ->
            when (e) {
                is ClientRequestException -> {
                    if (e.response.status == HttpStatusCode.Unauthorized) {
                        throw UnauthorizedError("Unauthorized (pairing token invalid). Reconnect from Settings → Connect to OpenCode.")
                    }
                    throw NetworkError(message = e.message, cause = e)
                }
                is ResponseException -> throw NetworkError(message = e.message, cause = e)
                else -> throw NetworkError(message = e.message, cause = e)
            }
        }
    }

    override suspend fun getSession(sessionId: String): Result<Session> {
        return runCatching {
            api.getSession(sessionId).toDomain()
        }.recoverCatching { e ->
            when (e) {
                is ClientRequestException -> when (e.response.status) {
                    HttpStatusCode.Unauthorized ->
                        throw UnauthorizedError("Unauthorized (pairing token invalid). Reconnect from Settings → Connect to OpenCode.")
                    HttpStatusCode.NotFound -> throw NotFoundError("Session not found: $sessionId")
                    else -> throw NetworkError(message = e.message, cause = e)
                }
                else -> when {
                    e.message?.contains("404") == true -> throw NotFoundError("Session not found: $sessionId")
                    else -> throw NetworkError(message = e.message, cause = e)
                }
            }
        }
    }

    override suspend fun getSessions(search: String?, limit: Int?, start: Long?): Result<List<Session>> {
        return runCatching {
            api.getSessions(search = search, limit = limit, start = start).map { it.toDomain() }
        }.recoverCatching { e ->
            when (e) {
                is ClientRequestException -> {
                    if (e.response.status == HttpStatusCode.Unauthorized) {
                        throw UnauthorizedError("Unauthorized (pairing token invalid). Reconnect from Settings → Connect to OpenCode.")
                    }
                    throw NetworkError(message = e.message, cause = e)
                }
                else -> throw NetworkError(message = e.message, cause = e)
            }
        }
    }

    override suspend fun createSession(title: String?, parentId: String?): Result<Session> {
        OcMobileLog.d(TAG, "[CREATE] ========== CREATE SESSION START ==========")
        OcMobileLog.d(TAG, "[CREATE] Input: title=$title, parentId=$parentId")
        val startTime = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            OcMobileLog.d(TAG, "[CREATE] >>> Sending POST /session with parentID=$parentId")
            val newSession = api.createSession(CreateSessionRequest(parentId = parentId, title = title)).toDomain()
            val apiDuration = Clock.System.now().toEpochMilliseconds() - startTime

            OcMobileLog.d(TAG, "[CREATE] <<< API response in ${apiDuration}ms:")
            OcMobileLog.d(TAG, "[CREATE]     newSessionId=${newSession.id}")
            OcMobileLog.d(TAG, "[CREATE]     parentId=${newSession.parentId}")
            OcMobileLog.d(TAG, "[CREATE]     title=${newSession.title}")

            OcMobileLog.d(TAG, "[CREATE] Persisting active session ID: ${newSession.id}")
            appSettings.setCurrentSessionId(newSession.id)
            OcMobileLog.d(TAG, "[CREATE] Active session ID saved")
            OcMobileLog.d(TAG, "[CREATE] ========== CREATE SESSION SUCCESS ==========")
            newSession
        }.recoverCatching { e ->
            val duration = Clock.System.now().toEpochMilliseconds() - startTime
            OcMobileLog.e(TAG, "[CREATE] ========== CREATE SESSION FAILED in ${duration}ms ==========")
            OcMobileLog.e(TAG, "[CREATE] Error type: ${e::class.simpleName}")
            OcMobileLog.e(TAG, "[CREATE] Error message: ${e.message}")
            OcMobileLog.e(TAG, "[CREATE] Stack trace:", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun forkSession(sessionId: String, messageId: String?): Result<Session> {
        OcMobileLog.d(TAG, "[FORK] ========== FORK SESSION START ==========")
        OcMobileLog.d(TAG, "[FORK] Input: sessionId=$sessionId, messageId=$messageId")
        val startTime = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            OcMobileLog.d(TAG, "[FORK] >>> Sending POST /session/$sessionId/fork with messageID=$messageId")
            val forkedSession = api.forkSession(sessionId, ForkSessionRequest(messageId)).toDomain()
            val apiDuration = Clock.System.now().toEpochMilliseconds() - startTime

            OcMobileLog.d(TAG, "[FORK] <<< API response in ${apiDuration}ms:")
            OcMobileLog.d(TAG, "[FORK]     newSessionId=${forkedSession.id}")
            OcMobileLog.d(TAG, "[FORK]     parentId=${forkedSession.parentId}")
            OcMobileLog.d(TAG, "[FORK]     title=${forkedSession.title}")
            OcMobileLog.d(TAG, "[FORK]     directory=${forkedSession.directory}")
            OcMobileLog.d(TAG, "[FORK]     createdAt=${forkedSession.createdAt}")

            // Automatically update the current session ID to the new forked session
            OcMobileLog.d(TAG, "[FORK] Persisting active session ID: $sessionId -> ${forkedSession.id}")
            appSettings.setCurrentSessionId(forkedSession.id)
            OcMobileLog.d(TAG, "[FORK] Active session ID saved")
            OcMobileLog.d(TAG, "[FORK] ========== FORK SESSION SUCCESS ==========")
            forkedSession
        }.recoverCatching { e ->
            val duration = Clock.System.now().toEpochMilliseconds() - startTime
            OcMobileLog.e(TAG, "[FORK] ========== FORK SESSION FAILED in ${duration}ms ==========")
            OcMobileLog.e(TAG, "[FORK] Error type: ${e::class.simpleName}")
            OcMobileLog.e(TAG, "[FORK] Error message: ${e.message}")
            OcMobileLog.e(TAG, "[FORK] Stack trace:", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun revertSession(sessionId: String, messageId: String): Result<Session> {
        OcMobileLog.d(TAG, "[REVERT] ========== REVERT SESSION START ==========")
        OcMobileLog.d(TAG, "[REVERT] Input: sessionId=$sessionId, messageId=$messageId")
        val startTime = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            OcMobileLog.d(TAG, "[REVERT] >>> Sending POST /session/$sessionId/revert with messageID=$messageId")
            val revertedSession = api.revertSession(sessionId, RevertSessionRequest(messageId)).toDomain()
            val apiDuration = Clock.System.now().toEpochMilliseconds() - startTime

            OcMobileLog.d(TAG, "[REVERT] <<< API response in ${apiDuration}ms:")
            OcMobileLog.d(TAG, "[REVERT]     sessionId=${revertedSession.id}")
            OcMobileLog.d(TAG, "[REVERT]     title=${revertedSession.title}")
            OcMobileLog.d(TAG, "[REVERT]     directory=${revertedSession.directory}")
            OcMobileLog.d(TAG, "[REVERT]     updatedAt=${revertedSession.updatedAt}")

            // No session ID update needed - we're staying in the same session
            OcMobileLog.d(TAG, "[REVERT] Session ID unchanged (same session): $sessionId")
            OcMobileLog.d(TAG, "[REVERT] ========== REVERT SESSION SUCCESS ==========")
            revertedSession
        }.recoverCatching { e ->
            val duration = Clock.System.now().toEpochMilliseconds() - startTime
            OcMobileLog.e(TAG, "[REVERT] ========== REVERT SESSION FAILED in ${duration}ms ==========")
            OcMobileLog.e(TAG, "[REVERT] Error type: ${e::class.simpleName}")
            OcMobileLog.e(TAG, "[REVERT] Error message: ${e.message}")
            OcMobileLog.e(TAG, "[REVERT] Stack trace:", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun updateCurrentSessionId(sessionId: String): Result<Unit> {
        return runCatching {
            appSettings.setCurrentSessionId(sessionId)
        }.recoverCatching { e ->
            throw NetworkError(message = e.message, cause = e)
        }
    }

    override suspend fun abortSession(sessionId: String): Result<Boolean> {
        OcMobileLog.d(TAG, "[ABORT] ========== ABORT SESSION START ==========")
        OcMobileLog.d(TAG, "[ABORT] Input: sessionId=$sessionId")
        val startTime = Clock.System.now().toEpochMilliseconds()

        return runCatching {
            OcMobileLog.d(TAG, "[ABORT] >>> Sending POST /session/$sessionId/abort")
            val success = api.abortSession(sessionId)
            val apiDuration = Clock.System.now().toEpochMilliseconds() - startTime

            OcMobileLog.d(TAG, "[ABORT] <<< API response in ${apiDuration}ms: success=$success")
            OcMobileLog.d(TAG, "[ABORT] ========== ABORT SESSION SUCCESS ==========")
            if (!success) {
                throw IllegalStateException("Abort returned false")
            }
            true
        }.recoverCatching { e ->
            val duration = Clock.System.now().toEpochMilliseconds() - startTime
            OcMobileLog.e(TAG, "[ABORT] ========== ABORT SESSION FAILED in ${duration}ms ==========")
            OcMobileLog.e(TAG, "[ABORT] Error type: ${e::class.simpleName}")
            OcMobileLog.e(TAG, "[ABORT] Error message: ${e.message}")
            OcMobileLog.e(TAG, "[ABORT] Stack trace:", e)
            throw NetworkError(message = e.message, cause = e)
        }
    }
}

/**
 * Extension function to convert SessionDto to domain Session.
 */
internal fun SessionDto.toDomain(): Session = Session(
    id = id,
    directory = directory,
    title = title,
    createdAt = Instant.fromEpochMilliseconds(time.created),
    updatedAt = Instant.fromEpochMilliseconds(time.updated),
    parentId = parentId,
    revert = revert?.let { dto ->
        com.ratulsarna.ocmobile.domain.model.SessionRevert(
            messageId = dto.messageId,
            partId = dto.partId,
            diff = dto.diff
        )
    }
)
