package com.ratulsarna.ocmobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * Phase 0: a tiny observable signal surface for verifying lifecycle behavior from SwiftUI.
 *
 * SwiftUI can observe these flows to confirm that:
 * - Kotlin Flows are consumable via SKIE, and
 * - AndroidX ViewModels are being cleared (onCleared invoked) when Swift-owned ViewModelStores clear.
 */
object SkieSmokeTestSignals {

    private val _clearedCount = MutableStateFlow(0)
    val clearedCount: StateFlow<Int> = _clearedCount.asStateFlow()

    private val _lastClearedAtEpochMs = MutableStateFlow(0L)
    val lastClearedAtEpochMs: StateFlow<Long> = _lastClearedAtEpochMs.asStateFlow()

    internal fun onCleared() {
        _clearedCount.value = _clearedCount.value + 1
        _lastClearedAtEpochMs.value = Clock.System.now().toEpochMilliseconds()
    }
}

