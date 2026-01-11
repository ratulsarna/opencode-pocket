package com.ratulsarna.ocmobile.domain.model

data class ProjectFileNode(
    val name: String,
    val path: String,
    val absolute: String,
    val type: ProjectFileNodeType,
    val ignored: Boolean
) {
    val isDirectory: Boolean get() = type == ProjectFileNodeType.DIRECTORY
    val isFile: Boolean get() = type == ProjectFileNodeType.FILE
}

enum class ProjectFileNodeType {
    FILE,
    DIRECTORY
}

data class ProjectFileStatus(
    val path: String,
    val type: ProjectFileStatusType
)

enum class ProjectFileStatusType {
    MODIFIED,
    ADDED,
    DELETED
}
