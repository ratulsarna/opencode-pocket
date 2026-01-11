package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.error.NetworkError
import com.ratulsarna.ocmobile.domain.error.NotFoundError
import com.ratulsarna.ocmobile.domain.model.FileTextContent
import com.ratulsarna.ocmobile.domain.repository.VaultFileRepository

class VaultFileRepositoryImpl(
    private val openCodeApi: OpenCodeApi,
    private val appSettings: AppSettings
) : VaultFileRepository {

    override suspend fun getWorktree(): Result<String> = runCatching {
        appSettings.getActiveWorkspaceSnapshot()?.worktree?.trim()?.takeIf { it.isNotBlank() }
            ?: openCodeApi.getCurrentProject().worktree
    }.recoverCatching { e ->
        throw NetworkError(message = e.message, cause = e)
    }

    override suspend fun normalizeToRelative(path: String): Result<String> = runCatching {
        if (!path.startsWith("/")) return@runCatching path

        val worktree = getWorktree().getOrThrow().trimEnd('/')
        if (path == worktree) {
            throw IllegalArgumentException("Path points to the worktree root.")
        }
        if (!path.startsWith("$worktree/")) {
            throw IllegalArgumentException("Path is outside the current worktree.")
        }
        path.removePrefix("$worktree/")
    }

    override suspend fun readTextFile(relativePath: String): Result<FileTextContent> = runCatching {
        val response = openCodeApi.getFileContent(relativePath)
        FileTextContent(
            path = relativePath,
            content = response.content,
            mimeType = response.mimeType,
            encoding = response.encoding
        )
    }.recoverCatching { e ->
        when {
            e.message?.contains("404") == true -> throw NotFoundError("File not found: $relativePath")
            else -> throw NetworkError(message = e.message, cause = e)
        }
    }
}
