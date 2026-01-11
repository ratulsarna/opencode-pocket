package com.ratulsarna.ocmobile.data.api

import com.ratulsarna.ocmobile.data.dto.*
import com.ratulsarna.ocmobile.util.OcMobileLog
import io.ktor.client.*
import kotlin.time.Clock
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private const val TAG = "OpenCodeApi"

/**
 * Ktor-based implementation of the OpenCode API.
 */
class OpenCodeApiImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : OpenCodeApi {

    override suspend fun sendMessage(
        sessionId: String,
        request: SendMessageRequest
    ): SendMessageResponse {
        val url = "$baseUrl/session/$sessionId/message"
        OcMobileLog.d(
            TAG,
            "[SEND] HTTP >>> POST $url " +
                "(parts=${request.parts.size}, " +
                "model=${request.model?.providerId}/${request.model?.modelId}, " +
                "agent=${request.agent})"
        )

        val startTime = Clock.System.now().toEpochMilliseconds()
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
            // Sending a message may block until the assistant finishes (depending on server impl),
            // while SSE continues streaming deltas. Avoid failing the POST mid-stream due to short timeouts.
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = 600_000L // 10 minutes
            }
        }
        val duration = Clock.System.now().toEpochMilliseconds() - startTime

        OcMobileLog.d(TAG, "[SEND] HTTP <<< ${response.status.value} ${response.status.description} in ${duration}ms")
        if (!response.status.isSuccess()) {
            // Avoid Ktor "No suitable converter found" by failing before attempting to deserialize.
            val rawBody = runCatching { response.bodyAsText() }.getOrNull()
            OcMobileLog.e(
                TAG,
                "[SEND] HTTP <<< ERROR: Non-success status ${response.status}. " +
                    "Body=${rawBody?.take(400)}"
            )
            val suffix = rawBody?.trim()?.takeIf { it.isNotEmpty() }?.let { ": ${it.take(200)}" }.orEmpty()
            error("Send failed: HTTP ${response.status.value}$suffix")
        }

        val contentType = response.contentType()
        if (contentType != null && !contentType.match(ContentType.Application.Json)) {
            val rawBody = runCatching { response.bodyAsText() }.getOrNull()
            OcMobileLog.e(
                TAG,
                "[SEND] HTTP <<< ERROR: Unexpected content-type $contentType. Body=${rawBody?.take(400)}"
            )
            val suffix = rawBody?.trim()?.takeIf { it.isNotEmpty() }?.let { ": ${it.take(200)}" }.orEmpty()
            error("Send failed: unexpected response content-type=$contentType$suffix")
        }

        return response.body()
    }

    override suspend fun getMessages(sessionId: String, limit: Int?, reverse: Boolean?): List<MessageWithPartsDto> {
        val url = "$baseUrl/session/$sessionId/message"
        return httpClient.get(url) {
            if (limit != null) parameter("limit", limit)
            // Server expects reverse as 1/0 (some deployments ignore "true"/"false")
            if (reverse != null) parameter("reverse", if (reverse) 1 else 0)
        }.body()
    }

    override suspend fun getSession(sessionId: String): SessionDto {
        return httpClient.get("$baseUrl/session/$sessionId").body()
    }

    override suspend fun getSessions(search: String?, limit: Int?, start: Long?): List<SessionDto> {
        return httpClient.get("$baseUrl/session") {
            if (start != null) parameter("start", start)
            if (!search.isNullOrBlank()) parameter("search", search)
            if (limit != null) parameter("limit", limit)
        }.body()
    }

    override suspend fun createSession(request: CreateSessionRequest): SessionDto {
        val url = "$baseUrl/session"
        OcMobileLog.d(TAG, "[CREATE] HTTP >>> POST $url parentID=${request.parentId} title=${request.title}")
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val rawBody = response.bodyAsText()
        OcMobileLog.d(TAG, "[CREATE] HTTP <<< ${response.status.value} ${response.status.description}")
        if (!response.status.isSuccess()) {
            OcMobileLog.e(TAG, "[CREATE] HTTP <<< ERROR: Non-success status ${response.status}. Body=${rawBody.take(400)}")
        }
        return Json { ignoreUnknownKeys = true }.decodeFromString<SessionDto>(rawBody)
    }

    override suspend fun forkSession(
        sessionId: String,
        request: ForkSessionRequest
    ): SessionDto {
        val url = "$baseUrl/session/$sessionId/fork"
        OcMobileLog.d(TAG, "[FORK] HTTP >>> POST $url")
        OcMobileLog.d(TAG, "[FORK] HTTP >>> Request body: messageID=${request.messageId}")

        val startTime = Clock.System.now().toEpochMilliseconds()
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val duration = Clock.System.now().toEpochMilliseconds() - startTime
        val rawBody = response.bodyAsText()

        OcMobileLog.d(TAG, "[FORK] HTTP <<< ${response.status.value} ${response.status.description} in ${duration}ms")

        if (!response.status.isSuccess()) {
            OcMobileLog.e(TAG, "[FORK] HTTP <<< ERROR: Non-success status ${response.status}")
        }

        return Json { ignoreUnknownKeys = true }.decodeFromString<SessionDto>(rawBody)
    }

    override suspend fun revertSession(
        sessionId: String,
        request: RevertSessionRequest
    ): SessionDto {
        val url = "$baseUrl/session/$sessionId/revert"
        OcMobileLog.d(TAG, "[REVERT] HTTP >>> POST $url")
        OcMobileLog.d(TAG, "[REVERT] HTTP >>> Request body: messageID=${request.messageId}, partID=${request.partId}")

        val startTime = Clock.System.now().toEpochMilliseconds()
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val duration = Clock.System.now().toEpochMilliseconds() - startTime
        val rawBody = response.bodyAsText()

        OcMobileLog.d(TAG, "[REVERT] HTTP <<< ${response.status.value} ${response.status.description} in ${duration}ms")

        if (!response.status.isSuccess()) {
            OcMobileLog.e(TAG, "[REVERT] HTTP <<< ERROR: Non-success status ${response.status}")
        }

        return Json { ignoreUnknownKeys = true }.decodeFromString<SessionDto>(rawBody)
    }

    override suspend fun getAgents(): List<AgentDto> {
        return httpClient.get("$baseUrl/agent").body()
    }

    override suspend fun getProviders(): ProviderListResponse {
        return httpClient.get("$baseUrl/provider").body()
    }

    override suspend fun getConfig(): ConfigResponse {
        return httpClient.get("$baseUrl/config").body()
    }

    override suspend fun getCurrentProject(directory: String?): ProjectInfoDto {
        return httpClient.get("$baseUrl/project/current") {
            val dir = directory?.trim()?.takeIf { it.isNotBlank() }
            if (dir != null) {
                // Override any directory derived from the defaultRequest directoryProvider.
                headers.remove("x-opencode-directory")
                header("x-opencode-directory", dir)
            }
        }.body()
    }

    override suspend fun getPath(): ServerPathDto {
        return httpClient.get("$baseUrl/path").body()
    }

    override suspend fun listProjects(): List<ProjectInfoDto> {
        return httpClient.get("$baseUrl/project").body()
    }

    override suspend fun getFileContent(path: String): FileContentDto {
        return httpClient.get("$baseUrl/file/content") {
            parameter("path", path)
        }.body()
    }

    override suspend fun listFiles(path: String): List<FileNodeDto> {
        return httpClient.get("$baseUrl/file") {
            parameter("path", path)
        }.body()
    }

    override suspend fun getFileStatus(): List<FileStatusDto> {
        return httpClient.get("$baseUrl/file/status").body()
    }

    override suspend fun abortSession(sessionId: String): Boolean {
        val url = "$baseUrl/session/$sessionId/abort"
        OcMobileLog.d(TAG, "[ABORT] HTTP >>> POST $url")

        val startTime = Clock.System.now().toEpochMilliseconds()
        val response = httpClient.post(url)
        val duration = Clock.System.now().toEpochMilliseconds() - startTime

        OcMobileLog.d(TAG, "[ABORT] HTTP <<< ${response.status.value} ${response.status.description} in ${duration}ms")

        if (!response.status.isSuccess()) {
            OcMobileLog.e(TAG, "[ABORT] HTTP <<< ERROR: Non-success status ${response.status}")
            // Do not treat non-2xx as success, even if the body is empty.
            return false
        }
        // Per OpenCode OpenAPI, /session/{sessionID}/abort returns a boolean body on 200.
        // We allow empty bodies for forward-compat (e.g. 204), but otherwise we fail-closed:
        // if we can't parse a boolean (or recognized boolean wrapper), treat it as failure.
        val raw = response.bodyAsText().trim()
        if (raw.isEmpty()) return true
        if (raw.equals("true", ignoreCase = true)) return true
        if (raw.equals("false", ignoreCase = true)) return false

        return runCatching {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val element = json.parseToJsonElement(raw)
            when (element) {
                is JsonPrimitive -> element.booleanOrNull
                is JsonObject -> {
                    // Common patterns: { "success": true }, { "ok": true }, { "aborted": true }
                    element["success"]?.jsonPrimitive?.booleanOrNull
                        ?: element["ok"]?.jsonPrimitive?.booleanOrNull
                        ?: element["aborted"]?.jsonPrimitive?.booleanOrNull
                }
                else -> null
            } ?: false
        }.getOrElse {
            OcMobileLog.w(TAG, "[ABORT] Unexpected response body for abort (treating as failure): ${raw.take(200)}")
            false
        }
    }

    override suspend fun findVaultFiles(query: String, includeDirs: Boolean): List<String> {
        val url = "$baseUrl/find/file"
        OcMobileLog.d(TAG, "[VAULT] HTTP >>> GET $url?query=$query&dirs=$includeDirs")
        val response = httpClient.get(url) {
            parameter("query", query)
            // OpenCode: GET /find/file?dirs=<bool> (default true)
            parameter("dirs", includeDirs)
        }
        OcMobileLog.d(TAG, "[VAULT] HTTP <<< ${response.status.value}")
        return response.body()
    }

    override suspend fun getPermissionRequests(): List<PermissionRequestDto> {
        val url = "$baseUrl/permission"
        return httpClient.get(url).body()
    }

    override suspend fun replyToPermissionRequest(requestId: String, request: PermissionReplyRequestDto) {
        val url = "$baseUrl/permission/$requestId/reply"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val rawBody = runCatching { response.bodyAsText() }.getOrNull()
            OcMobileLog.e(TAG, "[PERMISSION] HTTP <<< ERROR: ${response.status}. Body=${rawBody?.take(400)}")
            error("Permission reply failed: HTTP ${response.status.value}")
        }
    }

    override suspend fun listCommands(): List<CommandInfoDto> {
        val url = "$baseUrl/command"
        return httpClient.get(url).body()
    }

    override suspend fun sendCommand(sessionId: String, request: SendCommandRequest): SendMessageResponse {
        val url = "$baseUrl/session/$sessionId/command"
        OcMobileLog.d(
            TAG,
            "[COMMAND] HTTP >>> POST $url (command=${request.command}, argsLen=${request.arguments.length}, " +
                "model=${request.model}, agent=${request.agent}, parts=${request.parts?.size ?: 0})"
        )

        val startTime = Clock.System.now().toEpochMilliseconds()
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
            // Commands can involve long-running tool usage; avoid failing due to short timeouts.
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = 600_000L // 10 minutes
            }
        }
        val duration = Clock.System.now().toEpochMilliseconds() - startTime

        OcMobileLog.d(TAG, "[COMMAND] HTTP <<< ${response.status.value} ${response.status.description} in ${duration}ms")
        if (!response.status.isSuccess()) {
            val rawBody = runCatching { response.bodyAsText() }.getOrNull()
            OcMobileLog.e(
                TAG,
                "[COMMAND] HTTP <<< ERROR: Non-success status ${response.status}. Body=${rawBody?.take(400)}"
            )
            error("Command failed: HTTP ${response.status.value}")
        }

        return response.body()
    }
}

/**
 * Factory function to create fully configured API clients.
 *
 * Uses separate HttpClients for REST API calls and SSE connections:
 * - REST API: Standard timeouts for quick request/response cycles
 * - SSE: Long socket timeouts to support long-lived streaming connections
 */
fun createApiClients(
    baseUrl: String,
    directoryProvider: (() -> String?)? = null,
    authTokenProvider: (() -> String?)? = null,
    httpClient: HttpClient = createOpenCodeHttpClient(
        directoryProvider = directoryProvider,
        authTokenProvider = authTokenProvider
    ),
    sseHttpClient: HttpClient = createSseHttpClient(
        directoryProvider = directoryProvider,
        authTokenProvider = authTokenProvider
    )
): ApiClients {
    val openCodeApi = OpenCodeApiImpl(httpClient, baseUrl)
    val sseClient = OpenCodeSseClient(sseHttpClient, baseUrl)
    return ApiClients(
        openCodeApi = openCodeApi,
        sseClient = sseClient,
        httpClient = httpClient,
        sseHttpClient = sseHttpClient,
        baseUrl = baseUrl
    )
}

/**
 * Container for all API clients.
 */
data class ApiClients(
    val openCodeApi: OpenCodeApi,
    val sseClient: OpenCodeSseClient,
    internal val httpClient: HttpClient,
    internal val sseHttpClient: HttpClient,
    val baseUrl: String
) {
    fun close() {
        httpClient.close()
        sseHttpClient.close()
    }
}

/**
 * Factory function for creating a configured HttpClient for regular REST API calls.
 *
 * Timeouts are configured to fail fast and provide responsive UX:
 * - Connect: 10 seconds (detect unreachable servers quickly)
 * - Request: 30 seconds (covers most API calls)
 * - Socket: 60 seconds (allows for slower responses)
 */
fun createOpenCodeHttpClient(
    engine: io.ktor.client.engine.HttpClientEngine? = null,
    directoryProvider: (() -> String?)? = null,
    authTokenProvider: (() -> String?)? = null
): HttpClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    return if (engine != null) {
        HttpClient(engine) {
            expectSuccess = true
            defaultRequest {
                val token = authTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                if (token != null) {
                    headers[HttpHeaders.Authorization] = "Bearer $token"
                } else {
                    headers.remove(HttpHeaders.Authorization)
                }

                if (headers["x-opencode-directory"] == null) {
                    val dir = directoryProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                    if (dir != null) header("x-opencode-directory", dir)
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000L   // 10 seconds to establish connection
                requestTimeoutMillis = 30_000L   // 30 seconds for entire request
                socketTimeoutMillis = 60_000L    // 60 seconds for socket read
            }
        }
    } else {
        HttpClient {
            expectSuccess = true
            defaultRequest {
                val token = authTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                if (token != null) {
                    headers[HttpHeaders.Authorization] = "Bearer $token"
                } else {
                    headers.remove(HttpHeaders.Authorization)
                }

                if (headers["x-opencode-directory"] == null) {
                    val dir = directoryProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                    if (dir != null) header("x-opencode-directory", dir)
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000L   // 10 seconds to establish connection
                requestTimeoutMillis = 30_000L   // 30 seconds for entire request
                socketTimeoutMillis = 60_000L    // 60 seconds for socket read
            }
        }
    }
}

/**
 * Factory function for creating an HttpClient optimized for SSE (Server-Sent Events) connections.
 *
 * SSE connections are long-lived and may sit idle waiting for events. This client has:
 * - Very long socket timeout (10 minutes) to allow waiting for infrequent events
 * - Reasonable connect timeout (30 seconds) for establishing the connection
 * - No request timeout (infinite) since SSE streams are meant to stay open indefinitely
 *
 * Note: If your SSE server sends heartbeat/ping events more frequently, you can reduce
 * the socket timeout accordingly. The 10-minute timeout is a safe default.
 */
fun createSseHttpClient(
    engine: io.ktor.client.engine.HttpClientEngine? = null,
    directoryProvider: (() -> String?)? = null,
    authTokenProvider: (() -> String?)? = null
): HttpClient {
    return if (engine != null) {
        HttpClient(engine) {
            expectSuccess = true
            defaultRequest {
                val token = authTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                if (token != null) {
                    headers[HttpHeaders.Authorization] = "Bearer $token"
                } else {
                    headers.remove(HttpHeaders.Authorization)
                }

                if (headers["x-opencode-directory"] == null) {
                    val dir = directoryProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                    if (dir != null) header("x-opencode-directory", dir)
                }
            }
            install(HttpTimeout) {
                // Socket timeout (read timeout) - how long to wait for data on an open connection
                // SSE can be idle for long periods, so we use a very long timeout
                // 10 minutes = 600,000ms - adjust based on expected event frequency
                socketTimeoutMillis = 600_000L

                // Connect timeout - how long to wait when establishing connection
                // Reduced to 10s to detect unreachable servers quickly (was 30s)
                connectTimeoutMillis = 10_000L

                // Request timeout - total time for the request
                // SSE streams run indefinitely, so we disable this (Long.MAX_VALUE = no timeout)
                requestTimeoutMillis = Long.MAX_VALUE
            }
        }
    } else {
        HttpClient {
            expectSuccess = true
            defaultRequest {
                val token = authTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                if (token != null) {
                    headers[HttpHeaders.Authorization] = "Bearer $token"
                } else {
                    headers.remove(HttpHeaders.Authorization)
                }

                if (headers["x-opencode-directory"] == null) {
                    val dir = directoryProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
                    if (dir != null) header("x-opencode-directory", dir)
                }
            }
            install(HttpTimeout) {
                socketTimeoutMillis = 600_000L
                connectTimeoutMillis = 10_000L
                requestTimeoutMillis = Long.MAX_VALUE
            }
        }
    }
}
