package com.ratulsarna.ocmobile.data.mock

import com.ratulsarna.ocmobile.domain.model.Event
import com.ratulsarna.ocmobile.domain.repository.ConnectionState
import com.ratulsarna.ocmobile.domain.repository.EventStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mock implementation of EventStream.
 * Bypasses OpenCodeSseClient entirely - returns events directly from MockState's event bus.
 */
class MockEventStream(private val state: MockState) : EventStream {
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    override fun subscribeToEvents(): Flow<Event> = state.observeEvents()

    override fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
