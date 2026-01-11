package com.ratulsarna.ocmobile

/**
 * Build configuration for platform-specific values.
 * Used primarily for APNs environment detection (sandbox vs production).
 */
expect object BuildConfig {
    /**
     * True if this is a debug build, false for release.
     * On iOS: determines APNs environment (sandbox vs production).
     */
    val isDebug: Boolean
}
