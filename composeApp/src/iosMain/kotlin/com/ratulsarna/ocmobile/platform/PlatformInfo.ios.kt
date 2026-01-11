package com.ratulsarna.ocmobile.platform

actual object PlatformInfo {
    // iOS pasteboard reads are privacy-gated; polling is unreliable / may be blocked.
    actual val defaultClipboardPollingIntervalMs: Long = 0L
    actual val alwaysShowClipboardPasteOption: Boolean = true
    actual val useEnterToSend: Boolean = false
    actual val useRightClickForMessageActions: Boolean = false
    actual val supportsPushNotifications: Boolean = true
}

