package com.ratulsarna.ocmobile.ui.screen.docs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ratulsarna.ocmobile.domain.repository.VaultFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MarkdownFileViewerViewModel(
    private val inputPath: String,
    private val vaultFileRepository: VaultFileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarkdownFileViewerUiState(inputPath = inputPath))
    val uiState: StateFlow<MarkdownFileViewerUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun reload() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val normalized = vaultFileRepository.normalizeToRelative(inputPath)
            val relativePath = normalized.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to resolve file path."
                    )
                }
                return@launch
            }

            val result = vaultFileRepository.readTextFile(relativePath)
            val fileContent = result.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        relativePath = relativePath,
                        title = displayName(relativePath),
                        isMarkdown = isMarkdown(relativePath),
                        error = error.message ?: "Failed to load file."
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    relativePath = relativePath,
                    title = displayName(relativePath),
                    content = fileContent.content,
                    isMarkdown = isMarkdown(relativePath),
                    error = null
                )
            }
        }
    }

    private fun displayName(path: String): String {
        val name = path.substringAfterLast('/')
        return if (name.isNotBlank()) name else path
    }

    private fun isMarkdown(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".markdown")
    }
}

data class MarkdownFileViewerUiState(
    val inputPath: String,
    val relativePath: String? = null,
    val title: String = "File",
    val content: String = "",
    val isMarkdown: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)
