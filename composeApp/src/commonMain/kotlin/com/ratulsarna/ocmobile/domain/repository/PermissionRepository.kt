package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.PermissionReply
import com.ratulsarna.ocmobile.domain.model.PermissionRequest

interface PermissionRepository {
    suspend fun getPendingRequests(): Result<List<PermissionRequest>>

    suspend fun reply(
        requestId: String,
        reply: PermissionReply,
        message: String? = null
    ): Result<Unit>
}

