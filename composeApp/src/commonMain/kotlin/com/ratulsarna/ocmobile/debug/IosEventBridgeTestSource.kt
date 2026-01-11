package com.ratulsarna.ocmobile.debug

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * iOS-only debug/test helper for Phase 2.5 (SwiftUI UI-event bridge).
 *
 * This is intentionally simple:
 * - Swift can call tryEmit(...) synchronously.
 * - Swift can read subscriptionCount.value to assert collector lifecycle deterministically.
 */
class IosEventBridgeTestSource {
    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64
    )

    private val _subscriptionCount = MutableStateFlow(0)

    /**
     * A cold flow that increments/decrements [subscriptionCount] per active collector.
     *
     * We intentionally do not rely on `SharedFlow.subscriptionCount` because not all coroutine
     * versions expose it (and Phase 2.5 needs this to compile in the current repo).
     */
    val events: Flow<String> = flow {
        _subscriptionCount.value = _subscriptionCount.value + 1
        try {
            _events.asSharedFlow().collect { emit(it) }
        } finally {
            _subscriptionCount.value = (_subscriptionCount.value - 1).coerceAtLeast(0)
        }
    }

    val subscriptionCount: StateFlow<Int> = _subscriptionCount

    fun tryEmit(value: String): Boolean = _events.tryEmit(value)
}
