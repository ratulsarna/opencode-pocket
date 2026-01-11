package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.FileTextContent

/**
 * Repository for reading vault files via OpenCode.
 */
interface VaultFileRepository {
    /**
     * Get the current worktree path from OpenCode.
     */
    suspend fun getWorktree(): Result<String>

    /**
     * Read a text file by worktree-relative path.
     */
    suspend fun readTextFile(relativePath: String): Result<FileTextContent>

    /**
     * Normalize an absolute path to a worktree-relative path when possible.
     */
    suspend fun normalizeToRelative(path: String): Result<String>
}
