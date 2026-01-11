package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for subscribing to SSE events from the OpenCode server.
 */
interface EventStream {
    /**
     * Subscribe to global SSE events.
     * This is a cold flow that connects when collected.
     * Handles reconnection internally with exponential backoff.
     */
    fun subscribeToEvents(): Flow<Event>

    /**
     * Check if currently connected to the event stream.
     */
    val isConnected: Boolean

    /**
     * Observable connection state for UI display.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Disconnect from the event stream.
     */
    fun disconnect()
}

/**
 * Connection state for SSE event stream.
 */
enum class ConnectionState {
    /** Successfully connected and receiving events */
    CONNECTED,
    /** Not connected (initial state or after disconnect) */
    DISCONNECTED,
    /** Connection lost, attempting to reconnect with backoff */
    RECONNECTING
}
