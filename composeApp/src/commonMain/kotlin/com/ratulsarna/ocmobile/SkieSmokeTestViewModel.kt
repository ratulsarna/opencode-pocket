package com.ratulsarna.ocmobile

import androidx.lifecycle.ViewModel
import com.ratulsarna.ocmobile.data.api.ApiResult
import com.ratulsarna.ocmobile.domain.error.NetworkError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Phase 0: minimal ViewModel used to validate SwiftUI interop and lifecycle behavior.
 *
 * - Exposes a simple Flow ([counter]) to validate Flow observation in SwiftUI via SKIE.
 * - Exposes a suspend function ([delayedEcho]) to validate async/await + cancellation.
 * - Emits a signal via [SkieSmokeTestSignals] in [onCleared] so Swift can observe teardown.
 */
class SkieSmokeTestViewModel : ViewModel() {

    val counter: Flow<Int> = flow {
        var i = 0
        while (true) {
            emit(i++)
            delay(1_000)
        }
    }

    suspend fun delayedEcho(message: String, delayMs: Long): String {
        delay(delayMs)
        return message
    }

    /**
     * Spike: validate SKIE sealed bridging for a generic sealed result type.
     *
     * This function intentionally does not throw; it encodes success/failure as data.
     */
    suspend fun apiResultProbe(shouldFail: Boolean): ApiResult<String> {
        delay(100)
        return if (shouldFail) {
            ApiResult.Failure(NetworkError(message = "Probe failure (expected)"))
        } else {
            ApiResult.Success("Probe success (expected)")
        }
    }

    override fun onCleared() {
        SkieSmokeTestSignals.onCleared()
        super.onCleared()
    }
}
