package com.ratulsarna.ocmobile.platform

actual object PlatformInfo {
    // Desktop supports clipboard image detection via AWT, enable polling
    actual val defaultClipboardPollingIntervalMs: Long = 500L
    actual val alwaysShowClipboardPasteOption: Boolean = false
    actual val useEnterToSend: Boolean = true
    actual val useRightClickForMessageActions: Boolean = true
    actual val supportsPushNotifications: Boolean = false
}
