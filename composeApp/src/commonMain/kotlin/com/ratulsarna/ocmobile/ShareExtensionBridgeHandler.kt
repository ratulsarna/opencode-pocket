package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.domain.model.Attachment

/**
 * Handler for receiving shared content from platform-specific share extensions.
 * On iOS, this collects from ShareExtensionBridge.pendingPayload.
 * On other platforms, this is a no-op.
 */
expect object ShareExtensionBridgeHandler {
    /**
     * Starts collecting shared payloads and invokes the callback for each.
     * The callback receives attachments and optional shared text.
     * This is a suspending function that should be called from a LaunchedEffect.
     */
    suspend fun collectShares(onPayload: (attachments: List<Attachment>, text: String?) -> Unit)
}
