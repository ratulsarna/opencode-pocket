package com.ratulsarna.ocmobile.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

inline fun CoroutineScope.launchCatchingCancellable(
    crossinline onError: (Throwable) -> Unit,
    crossinline block: suspend CoroutineScope.() -> Unit
): Job {
    return launch {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onError(t)
        }
    }
}

suspend inline fun <T> runCatchingCancellable(
    crossinline block: suspend () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Result.failure(t)
    }
}

