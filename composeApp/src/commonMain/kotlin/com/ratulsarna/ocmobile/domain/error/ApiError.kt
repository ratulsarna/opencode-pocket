package com.ratulsarna.ocmobile.domain.error

/**
 * Sealed class representing API errors from OpenCode.
 */
sealed class ApiError : Exception() {
    abstract override val message: String?
}

/**
 * Authentication error with a provider (e.g., Anthropic, OpenAI).
 */
data class ProviderAuthError(
    val providerId: String?,
    override val message: String?
) : ApiError()

/**
 * Message was aborted before completion.
 */
data class MessageAbortedError(
    override val message: String?
) : ApiError()

/**
 * Output exceeded maximum token length.
 */
data class MessageOutputLengthError(
    override val message: String? = "Output exceeded maximum length"
) : ApiError()

/**
 * Generic API error from the server.
 */
data class GenericApiError(
    val errorName: String,
    override val message: String?,
    val statusCode: Int? = null,
    val isRetryable: Boolean = false
) : ApiError()

/**
 * Network-level error (connection failed, timeout, etc.).
 */
data class NetworkError(
    override val message: String?,
    override val cause: Throwable? = null
) : ApiError()

/**
 * Request was rejected by the server because credentials are missing or invalid.
 * For oc-pocket this usually means the pairing token has changed and the user must re-pair.
 */
data class UnauthorizedError(
    override val message: String?
) : ApiError()

/**
 * Error parsing response data.
 */
data class ParseError(
    override val message: String?,
    val rawData: String? = null
) : ApiError()

/**
 * Resource not found (session, message, etc.).
 */
data class NotFoundError(
    override val message: String?
) : ApiError()
