package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.ProjectFileNode
import com.ratulsarna.ocmobile.domain.model.ProjectFileStatus

interface ProjectFileRepository {
    suspend fun listDirectory(path: String): Result<List<ProjectFileNode>>
    suspend fun getStatus(): Result<Map<String, ProjectFileStatus>>
}
