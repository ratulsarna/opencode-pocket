package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.FileNodeDto
import com.ratulsarna.ocmobile.data.dto.FileStatusDto
import com.ratulsarna.ocmobile.domain.error.NetworkError
import com.ratulsarna.ocmobile.domain.model.ProjectFileNode
import com.ratulsarna.ocmobile.domain.model.ProjectFileNodeType
import com.ratulsarna.ocmobile.domain.model.ProjectFileStatus
import com.ratulsarna.ocmobile.domain.model.ProjectFileStatusType
import com.ratulsarna.ocmobile.domain.repository.ProjectFileRepository

class ProjectFileRepositoryImpl(
    private val api: OpenCodeApi
) : ProjectFileRepository {

    override suspend fun listDirectory(path: String): Result<List<ProjectFileNode>> = runCatching {
        api.listFiles(path)
            .map { it.toDomain() }
            .sortedWith(compareBy<ProjectFileNode> { it.type.sortOrder }.thenBy { it.name.lowercase() })
    }.recoverCatching { e ->
        throw NetworkError(message = e.message, cause = e)
    }

    override suspend fun getStatus(): Result<Map<String, ProjectFileStatus>> = runCatching {
        api.getFileStatus()
            .mapNotNull { it.toDomainOrNull() }
            .associateBy { it.path }
    }.recoverCatching { e ->
        throw NetworkError(message = e.message, cause = e)
    }
}

private val ProjectFileNodeType.sortOrder: Int
    get() = when (this) {
        ProjectFileNodeType.DIRECTORY -> 0
        ProjectFileNodeType.FILE -> 1
    }

private fun FileNodeDto.toDomain(): ProjectFileNode {
    return ProjectFileNode(
        name = name,
        path = path,
        absolute = absolute,
        type = when (type.lowercase()) {
            "directory", "dir", "folder" -> ProjectFileNodeType.DIRECTORY
            else -> ProjectFileNodeType.FILE
        },
        ignored = ignored
    )
}

private fun FileStatusDto.toDomainOrNull(): ProjectFileStatus? {
    val normalized = status.trim().lowercase()
    val type = when {
        normalized == "m" || normalized == "modified" || normalized == "change" || normalized == "changed" ->
            ProjectFileStatusType.MODIFIED
        normalized == "a" || normalized == "added" || normalized == "add" || normalized == "new" ->
            ProjectFileStatusType.ADDED
        normalized == "d" || normalized == "deleted" || normalized == "delete" || normalized == "removed" ->
            ProjectFileStatusType.DELETED
        else -> null
    }
    return type?.let { ProjectFileStatus(path = path, type = it) }
}
