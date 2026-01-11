package com.ratulsarna.ocmobile.ui.screen.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.data.api.createApiClients
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.model.PairingPayload
import com.ratulsarna.ocmobile.domain.repository.ServerRepository
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConnectViewModel(
    private val serverRepository: ServerRepository,
    private val appSettings: AppSettings,
    private val validateConnection: suspend (baseUrl: String, token: String) -> Result<Unit> = { baseUrl, token ->
        runCatching {
            val clients = createApiClients(
                baseUrl = baseUrl.trimEnd('/'),
                directoryProvider = null,
                authTokenProvider = { token }
            )
            try {
                clients.openCodeApi.getPath()
                Unit
            } finally {
                clients.close()
            }
        }.recoverCatching { err ->
            when (err) {
                is ClientRequestException -> {
                    if (err.response.status == HttpStatusCode.Unauthorized) {
                        error("Unauthorized (token invalid)")
                    } else {
                        throw err
                    }
                }
                is ConnectTimeoutException -> error("Cannot reach server (connect timeout)")
                is SocketTimeoutException -> error("Cannot reach server (timeout)")
                else -> {
                    val message = err.message.orEmpty()
                    if (
                        message.contains("Local network prohibited", ignoreCase = true) ||
                        message.contains("NSURLErrorNWPathKey=unsatisfied", ignoreCase = true)
                    ) {
                        throw LocalNetworkBlockedException()
                    }
                    throw err
                }
            }
        }
    }
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    private var pairingRevision: Int = 0
    private var disconnectRevision: Int = 0
    private var autoRetryJob: Job? = null
    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            appSettings.getAuthTokenForActiveServer()
                .map { token -> !token.isNullOrBlank() }
                .distinctUntilChanged()
                .collect { hasToken ->
                    updateUiState { it.copy(hasAuthTokenForActiveServer = hasToken) }
                }
        }

        viewModelScope.launch {
            combine(
                serverRepository.getServers(),
                serverRepository.getActiveServerId()
            ) { servers, activeId ->
                servers.firstOrNull { it.id == activeId }
            }
                .distinctUntilChanged()
                .collect { active ->
                    updateUiState {
                        it.copy(
                            activeServerName = active?.name,
                            activeServerBaseUrl = active?.baseUrl
                        )
                    }
                }
        }
    }

    fun disconnect() {
        pairingRevision += 1
        autoRetryJob?.cancel()
        autoRetryJob = null
        connectJob?.cancel()
        connectJob = null

        viewModelScope.launch {
            runCatching { appSettings.resetConnectionState() }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                    updateUiState { it.copy(error = err.message ?: "Failed to disconnect") }
                }
                .onSuccess {
                    disconnectRevision += 1
                    updateUiState {
                        it.copy(
                            pairingString = "",
                            parsed = null,
                            isConnecting = false,
                            isAwaitingLocalNetworkPermission = false,
                            error = null,
                            connectedServerId = null,
                            disconnectRevision = disconnectRevision
                        )
                    }
                }
        }
    }

    fun setPairingString(raw: String) {
        pairingRevision += 1
        autoRetryJob?.cancel()
        autoRetryJob = null
        connectJob?.cancel()
        connectJob = null

        val trimmed = raw.trim()
        val parsed = PairingPayload.decode(trimmed).getOrNull()
        updateUiState {
            it.copy(
                pairingString = raw,
                parsed = parsed,
                isConnecting = false,
                isAwaitingLocalNetworkPermission = false,
                error = null,
                connectedServerId = null
            )
        }
    }

    fun connect() {
        connectInternal(ConnectAttempt.USER)
    }

    private fun startLocalNetworkAutoRetry(revisionAtStart: Int) {
        if (autoRetryJob?.isActive == true) return
        autoRetryJob = viewModelScope.launch {
            // iOS may show the "Local Network" permission prompt on top of the app. The initial request will fail with
            // "Local network prohibited". Once the user taps Allow, a retry should succeed.
            repeat(20) {
                delay(1_000)
                if (pairingRevision != revisionAtStart) return@launch
                if (uiState.value.connectedServerId != null) return@launch
                if (uiState.value.isConnecting) return@launch
                if (!uiState.value.isAwaitingLocalNetworkPermission) return@launch
                connectInternal(ConnectAttempt.AUTO_RETRY)
            }
        }
    }

    private fun connectInternal(attempt: ConnectAttempt) {
        if (connectJob?.isActive == true) return

        if (attempt == ConnectAttempt.USER) {
            updateUiState { state ->
                if (state.isConnecting) {
                    state
                } else {
                    state.copy(
                        isConnecting = true,
                        isAwaitingLocalNetworkPermission = false,
                        error = null,
                        connectedServerId = null
                    )
                }
            }
        }

        connectJob = viewModelScope.launch {
            val revisionAtStart = pairingRevision
            val raw = uiState.value.pairingString
            val payload = PairingPayload.decode(raw)
                .getOrElse { err ->
                    updateUiState { it.copy(isConnecting = false, error = err.message ?: "Invalid pairing string") }
                    return@launch
                }

            validateConnection(payload.baseUrl, payload.token)
                .onFailure { err ->
                    if (pairingRevision != revisionAtStart) return@onFailure
                    if (err is LocalNetworkBlockedException) {
                        // If we're already showing the "waiting" hint, keep state stable (no re-emit).
                        val isAlreadyWaiting = uiState.value.isAwaitingLocalNetworkPermission
                        if (!isAlreadyWaiting && attempt == ConnectAttempt.USER) {
                            updateUiState {
                                it.copy(
                                    isConnecting = false,
                                    isAwaitingLocalNetworkPermission = true,
                                    error = err.message,
                                    connectedServerId = null
                                )
                            }
                        } else if (!isAlreadyWaiting && attempt == ConnectAttempt.AUTO_RETRY) {
                            // A background retry discovered we still don't have permission; keep UI stable.
                            updateUiState {
                                it.copy(
                                    isConnecting = false,
                                    isAwaitingLocalNetworkPermission = true,
                                    error = err.message
                                )
                            }
                        } else if (attempt == ConnectAttempt.USER) {
                            updateUiState { it.copy(isConnecting = false) }
                        }
                        startLocalNetworkAutoRetry(revisionAtStart)
                        return@onFailure
                    }

                    updateUiState {
                        it.copy(
                            isConnecting = false,
                            isAwaitingLocalNetworkPermission = false,
                            error = err.message ?: "Failed to connect"
                        )
                    }
                }
                .onSuccess {
                    if (pairingRevision != revisionAtStart) return@onSuccess
                    val serverName = payload.name ?: "OpenCode"
                    runCatching { appSettings.resetConnectionState() }
                        .onFailure { err ->
                            if (pairingRevision != revisionAtStart) return@onFailure
                            if (err is CancellationException) throw err
                            updateUiState {
                                it.copy(
                                    isConnecting = false,
                                    isAwaitingLocalNetworkPermission = false,
                                    error = err.message ?: "Failed to reset local state"
                                )
                            }
                            return@launch
                        }
                    serverRepository.addServer(name = serverName, baseUrlInput = payload.baseUrl)
                        .onFailure { err ->
                            if (pairingRevision != revisionAtStart) return@onFailure
                            updateUiState {
                                it.copy(
                                    isConnecting = false,
                                    isAwaitingLocalNetworkPermission = false,
                                    error = err.message ?: "Failed to save server"
                                )
                            }
                        }
                        .onSuccess { profile ->
                            if (pairingRevision != revisionAtStart) return@onSuccess
                            // Always create a new profile on pairing (even if baseUrl matches existing).
                            appSettings.setAuthTokenForServer(profile.id, payload.token)
                            serverRepository.setActiveServer(profile.id)
                                .onFailure { err ->
                                    if (pairingRevision != revisionAtStart) return@onFailure
                                    updateUiState {
                                        it.copy(
                                            isConnecting = false,
                                            isAwaitingLocalNetworkPermission = false,
                                            error = err.message ?: "Failed to activate server"
                                        )
                                    }
                                }
                                .onSuccess {
                                    if (pairingRevision != revisionAtStart) return@onSuccess
                                    autoRetryJob?.cancel()
                                    autoRetryJob = null
                                    updateUiState {
                                        it.copy(
                                            isConnecting = false,
                                            isAwaitingLocalNetworkPermission = false,
                                            error = null,
                                            connectedServerId = profile.id
                                        )
                                    }
                                }
                        }
                }
        }
    }

    private inline fun updateUiState(transform: (ConnectUiState) -> ConnectUiState) {
        _uiState.update { current ->
            val next = transform(current)
            if (next == current) current else next
        }
    }
}

data class ConnectUiState(
    val pairingString: String = "",
    val parsed: PairingPayload? = null,
    val activeServerName: String? = null,
    val activeServerBaseUrl: String? = null,
    val hasAuthTokenForActiveServer: Boolean = false,
    val isAwaitingLocalNetworkPermission: Boolean = false,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val connectedServerId: String? = null,
    val disconnectRevision: Int = 0
)

private class LocalNetworkBlockedException : Exception(
    "Waiting for Local Network permission. Tap Allow on the iOS prompt."
)

private enum class ConnectAttempt {
    USER,
    AUTO_RETRY
}
