package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.domain.model.Attachment
import kotlinx.coroutines.flow.filterNotNull

/**
 * iOS implementation of ShareExtensionBridgeHandler.
 * Collects from ShareExtensionBridge.pendingPayload StateFlow.
 */
actual object ShareExtensionBridgeHandler {
    actual suspend fun collectShares(onPayload: (attachments: List<Attachment>, text: String?) -> Unit) {
        ShareExtensionBridge.pendingPayload
            .filterNotNull()
            .collect { payload ->
                onPayload(payload.attachments, payload.text)
                // ACK: Clear payload to prevent re-delivery
                ShareExtensionBridge.clearPendingPayload()
            }
    }
}
