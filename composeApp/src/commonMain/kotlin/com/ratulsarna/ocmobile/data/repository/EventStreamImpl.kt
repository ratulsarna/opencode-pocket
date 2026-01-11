package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeSseClient
import com.ratulsarna.ocmobile.domain.model.Event
import com.ratulsarna.ocmobile.domain.repository.ConnectionState
import com.ratulsarna.ocmobile.domain.repository.EventStream
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.shareIn
import com.ratulsarna.ocmobile.util.OcMobileLog
import kotlinx.coroutines.isActive
import kotlin.random.Random

private const val TAG = "EventStream"

/**
 * Implementation of EventStream using OpenCode SSE client.
 * Handles automatic reconnection with exponential backoff.
 *
 * @param sseClient The underlying SSE client
 * @param initialDelayMs Initial delay before first retry (default: 1 second)
 * @param maxDelayMs Maximum delay between retries (default: 5 seconds - kept low for responsive reconnection)
 * @param multiplier Multiplier for exponential backoff (default: 2.0)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventStreamImpl(
    private val sseClient: OpenCodeSseClient,
    scope: CoroutineScope,
    /**
     * Optional signal that should trigger an SSE reconnect when it changes (e.g. active workspace worktree).
     *
     * SSE connections are scoped at connection time (headers are not "live"), so when directory context changes we must
     * reconnect to receive updates for the new instance/project.
     */
    reconnectKey: Flow<String?>? = null,
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 5_000,
    private val multiplier: Double = 2.0
) : EventStream {

    private val _isConnected = MutableStateFlow(false)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    override val isConnected: Boolean
        get() = _isConnected.value

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val sharedEvents: SharedFlow<Event> = (reconnectKey?.distinctUntilChanged() ?: flowOf(null))
        .flatMapLatest {
            // Restart the underlying SSE connection whenever the key changes.
            createEventFlow()
        }
        .shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 0
        )

    override fun subscribeToEvents(): Flow<Event> = sharedEvents

    // TODO: Consider extracting shared exponential backoff logic (also in SamSessionIdStream)
    private fun createEventFlow(): Flow<Event> = flow {
        var currentDelay = initialDelayMs

        while (currentCoroutineContext().isActive) {
            var didConnect = false
            try {
                OcMobileLog.d(TAG, "Connecting to OpenCode SSE...")
                _connectionState.value = ConnectionState.RECONNECTING
                _isConnected.value = false

                sseClient.subscribeToEvents { status ->
                    if (!didConnect && status.value in 200..299) {
                        didConnect = true
                        _connectionState.value = ConnectionState.CONNECTED
                        _isConnected.value = true
                    }
                }.collect { event ->
                    emit(event)
                }

                // Normal completion (server closed connection) - prepare to reconnect
                OcMobileLog.d(TAG, "Connection closed normally, will reconnect")
                _connectionState.value = ConnectionState.RECONNECTING
                _isConnected.value = false

                // Reset delay only if we had a stable connection (stream opened successfully)
                if (didConnect) {
                    currentDelay = initialDelayMs
                }

                // Apply same backoff as error case to prevent tight reconnection loop
                val jitter = (Random.nextDouble() * 0.4 - 0.2) * currentDelay
                delay((currentDelay + jitter).toLong())
                currentDelay = (currentDelay * multiplier).coerceAtMost(maxDelayMs.toDouble()).toLong()

            } catch (e: CancellationException) {
                // Propagate cancellation (viewModelScope cancelled, etc.)
                OcMobileLog.d(TAG, "Cancelled")
                throw e
            } catch (e: Exception) {
                if (e is ClientRequestException && e.response.status == HttpStatusCode.Unauthorized) {
                    // Unauthorized will not recover with retries; stop reconnection loop so UI can guide the user to re-pair.
                    OcMobileLog.e(TAG, "SSE unauthorized; stopping reconnect loop")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _isConnected.value = false
                    return@flow
                }
                // Connection error - attempt reconnect with backoff
                OcMobileLog.e(TAG, "Connection error: ${e.message}, will reconnect")
                _connectionState.value = ConnectionState.RECONNECTING
                _isConnected.value = false

                // Reset delay only if we had a stable connection (stream opened successfully)
                if (didConnect) {
                    currentDelay = initialDelayMs
                }

                // Exponential backoff with jitter (+/- 20%)
                val jitter = (Random.nextDouble() * 0.4 - 0.2) * currentDelay
                delay((currentDelay + jitter).toLong())
                currentDelay = (currentDelay * multiplier).coerceAtMost(maxDelayMs.toDouble()).toLong()
            }
        }
    }.onCompletion {
        _connectionState.value = ConnectionState.DISCONNECTED
        _isConnected.value = false
    }

    override fun disconnect() {
        _isConnected.value = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
