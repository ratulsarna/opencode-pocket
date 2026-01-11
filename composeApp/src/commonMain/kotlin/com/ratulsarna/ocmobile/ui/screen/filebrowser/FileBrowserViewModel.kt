package com.ratulsarna.ocmobile.ui.screen.filebrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.domain.model.FileWatcherUpdatedEvent
import com.ratulsarna.ocmobile.domain.model.ProjectFileNode
import com.ratulsarna.ocmobile.domain.model.ProjectFileNodeType
import com.ratulsarna.ocmobile.domain.model.ProjectFileStatus
import com.ratulsarna.ocmobile.domain.model.ProjectFileStatusType
import com.ratulsarna.ocmobile.domain.repository.EventStream
import com.ratulsarna.ocmobile.domain.repository.ProjectFileRepository
import com.ratulsarna.ocmobile.domain.repository.VaultFileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ROOT_PATH = "."
private const val WATCHER_DEBOUNCE_MS = 350L

class FileBrowserViewModel(
    private val projectFileRepository: ProjectFileRepository,
    private val eventStream: EventStream,
    private val vaultFileRepository: VaultFileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val childrenByDir = mutableMapOf<String, List<ProjectFileNode>>()
    private val expandedDirs = mutableSetOf<String>()
    private val loadingDirs = mutableSetOf<String>()
    private var statusByPath: Map<String, ProjectFileStatus> = emptyMap()

    private val pendingWatcherDirs = mutableSetOf<String>()
    private var watcherJob: Job? = null

    init {
        refresh()
        subscribeToEvents()
    }

    fun toggleDirectory(path: String) {
        if (expandedDirs.contains(path)) {
            expandedDirs.remove(path)
            updateRows()
            return
        }

        expandedDirs.add(path)
        updateRows()

        if (!childrenByDir.containsKey(path)) {
            loadDirectory(path, force = false)
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return

        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val dirsToReload = buildList {
                add(ROOT_PATH)
                addAll(expandedDirs)
            }
            reloadDirectories(
                paths = dirsToReload,
                refreshStatus = true,
                finalizeRefresh = true
            )
        }
    }

    private fun loadDirectory(path: String, force: Boolean) {
        if (!force && childrenByDir.containsKey(path)) return
        viewModelScope.launch {
            reloadDirectories(
                paths = listOf(path),
                refreshStatus = false,
                finalizeRefresh = false
            )
        }
    }

    private suspend fun reloadDirectories(
        paths: List<String>,
        refreshStatus: Boolean,
        finalizeRefresh: Boolean
    ) {
        val uniquePaths = paths.distinct()
        if (uniquePaths.isEmpty()) {
            if (finalizeRefresh) {
                _uiState.update { it.copy(isRefreshing = false) }
            }
            return
        }

        loadingDirs.addAll(uniquePaths)
        updateRows()

        var errorMessage: String? = null

        try {
            coroutineScope {
                val dirRequests = uniquePaths.associateWith { path ->
                    async { projectFileRepository.listDirectory(path) }
                }
                val statusRequest = if (refreshStatus) {
                    async { projectFileRepository.getStatus() }
                } else {
                    null
                }

                dirRequests.forEach { (path, deferred) ->
                    deferred.await()
                        .onSuccess { childrenByDir[path] = it }
                        .onFailure { errorMessage = it.message }
                }

                statusRequest?.await()
                    ?.onSuccess { statusByPath = it }
                    ?.onFailure { errorMessage = it.message }
            }
        } finally {
            loadingDirs.removeAll(uniquePaths)

            _uiState.update { state ->
                state.copy(
                    rows = buildRows(),
                    statusByPath = statusByPath,
                    loadingDirectories = loadingDirs.toSet(),
                    isRefreshing = if (finalizeRefresh) false else state.isRefreshing,
                    error = errorMessage
                )
            }
        }
    }

    private fun subscribeToEvents() {
        viewModelScope.launch {
            eventStream.subscribeToEvents().collect { event ->
                if (event is FileWatcherUpdatedEvent) {
                    handleWatcherEvent(event)
                }
            }
        }
    }

    private fun handleWatcherEvent(event: FileWatcherUpdatedEvent) {
        viewModelScope.launch {
            vaultFileRepository.normalizeToRelative(event.file)
                .onSuccess { relativePath ->
                    val parentDir = relativePath.substringBeforeLast('/', missingDelimiterValue = ROOT_PATH)
                    val shouldRefresh = parentDir == ROOT_PATH || childrenByDir.containsKey(parentDir)
                    if (shouldRefresh) {
                        scheduleWatcherRefresh(parentDir)
                    }
                }
        }
    }

    private fun scheduleWatcherRefresh(directory: String) {
        pendingWatcherDirs.add(directory)
        watcherJob?.cancel()

        watcherJob = viewModelScope.launch {
            delay(WATCHER_DEBOUNCE_MS)
            val dirs = pendingWatcherDirs.toList()
            pendingWatcherDirs.clear()
            reloadDirectories(
                paths = dirs,
                refreshStatus = true,
                finalizeRefresh = false
            )
        }
    }

    private fun updateRows() {
        _uiState.update { state ->
            state.copy(
                rows = buildRows(),
                statusByPath = statusByPath,
                loadingDirectories = loadingDirs.toSet()
            )
        }
    }

    private fun buildRows(): List<FileTreeRow> {
        val rows = mutableListOf<FileTreeRow>()
        val rootChildren = childrenByDir[ROOT_PATH].orEmpty()
        rootChildren.forEach { child ->
            appendRow(child, depth = 0, rows = rows)
        }
        return rows
    }

    private fun appendRow(node: ProjectFileNode, depth: Int, rows: MutableList<FileTreeRow>) {
        val isExpanded = node.type == ProjectFileNodeType.DIRECTORY && expandedDirs.contains(node.path)
        val status = statusByPath[node.path]?.type
        rows.add(
            FileTreeRow(
                path = node.path,
                name = node.name,
                depth = depth,
                type = node.type,
                isExpanded = isExpanded,
                isLoading = loadingDirs.contains(node.path),
                ignored = node.ignored,
                status = status
            )
        )

        if (node.type == ProjectFileNodeType.DIRECTORY && isExpanded) {
            val children = childrenByDir[node.path].orEmpty()
            children.forEach { child ->
                appendRow(child, depth + 1, rows)
            }
        }
    }
}

data class FileBrowserUiState(
    val rows: List<FileTreeRow> = emptyList(),
    val statusByPath: Map<String, ProjectFileStatus> = emptyMap(),
    val loadingDirectories: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val error: String? = null
)

data class FileTreeRow(
    val path: String,
    val name: String,
    val depth: Int,
    val type: ProjectFileNodeType,
    val isExpanded: Boolean,
    val isLoading: Boolean,
    val ignored: Boolean,
    val status: ProjectFileStatusType?
)
