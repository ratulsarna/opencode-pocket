package com.ratulsarna.ocmobile

actual object BuildConfig {
    /**
     * Returns true for debug builds, false for release.
     *
     * On Desktop/JVM, this checks the "debug" system property.
     * Pass -Ddebug=false when running release builds.
     * Defaults to true (debug mode) if not specified.
     */
    actual val isDebug: Boolean = System.getProperty("debug")?.toBoolean() ?: true
}
