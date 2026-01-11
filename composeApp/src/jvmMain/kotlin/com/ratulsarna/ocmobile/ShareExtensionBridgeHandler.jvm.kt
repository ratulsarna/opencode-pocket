package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.domain.model.Attachment

/**
 * No-op implementation for JVM.
 * Share extensions are iOS-specific.
 */
actual object ShareExtensionBridgeHandler {
    actual suspend fun collectShares(onPayload: (attachments: List<Attachment>, text: String?) -> Unit) {
        kotlinx.coroutines.awaitCancellation()
    }
}

