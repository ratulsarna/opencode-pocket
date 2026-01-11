package com.ratulsarna.ocmobile.platform

/**
 * Small platform capability flags.
 *
 * iOS clipboard access is privacy-gated and unreliable for background polling. We therefore:
 * - disable clipboard polling on iOS
 * - always show the "Paste from Clipboard" action, and attempt to read only on user tap
 */
expect object PlatformInfo {
    /** Default clipboard polling interval for the platform. Set to 0 to disable polling. */
    val defaultClipboardPollingIntervalMs: Long

    /**
     * If true, the UI should always show the clipboard paste option (even if we can't
     * reliably detect clipboard contents ahead of time).
     */
    val alwaysShowClipboardPasteOption: Boolean

    /**
     * If true, pressing Enter in chat input sends the message (Shift+Enter for new line).
     * If false, Enter creates a new line (standard mobile behavior).
     */
    val useEnterToSend: Boolean

    /**
     * If true, message actions (revert/fork/copy) are triggered by right-click (secondary click).
     * If false, message actions are triggered by long-press (mobile behavior).
     */
    val useRightClickForMessageActions: Boolean

    /**
     * If true, the platform supports push notifications (APNs on iOS).
     * Used to conditionally show push notification settings.
     */
    val supportsPushNotifications: Boolean
}

