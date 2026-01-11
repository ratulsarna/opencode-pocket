package com.ratulsarna.ocmobile.data.api

import com.ratulsarna.ocmobile.domain.error.ApiError

/**
 * Project-owned Result type intended to be consumed from Swift via SKIE sealed bridging.
 *
 * Design goals:
 * - Treat expected failures (network down, timeouts, non-2xx responses) as data, not exceptions.
 * - Keep cancellation as cancellation (callers may still see CancellationException).
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

