package com.ratulsarna.ocmobile.util

import com.ratulsarna.ocmobile.BuildConfig
import com.ratulsarna.ocmobile.platform.PlatformInfo

/**
 * Simple centralized logger for opencode-pocket.
 * All logs are prefixed with [oc-pocket] followed by the component tag.
 *
 * Usage: OcMobileLog.d("ChatVM", "message here")
 * Output: [oc-pocket][ChatVM] message here
 */
object OcMobileLog {
    const val APP_TAG = "[oc-pocket]"

    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * Minimum log level to emit.
     *
     * Default: be verbose on desktop/web, quieter on mobile to avoid jank (especially during streaming updates).
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var minimumLevel: Level = when {
        PlatformInfo.useRightClickForMessageActions -> Level.DEBUG
        BuildConfig.isDebug -> Level.WARN
        else -> Level.WARN
    }

    fun isLoggable(level: Level): Boolean = level.ordinal >= minimumLevel.ordinal

    /** Debug level logging (message built eagerly). Prefer the lambda overload in hot paths. */
    fun d(tag: String, message: String) = d(tag) { message }

    /** Debug level logging (message built lazily). */
    inline fun d(tag: String, message: () -> String) {
        if (isLoggable(Level.DEBUG)) println("$APP_TAG[$tag] ${message()}")
    }

    /** Info level logging (message built eagerly). Prefer the lambda overload in hot paths. */
    fun i(tag: String, message: String) = i(tag) { message }

    /** Info level logging (message built lazily). */
    inline fun i(tag: String, message: () -> String) {
        if (isLoggable(Level.INFO)) println("$APP_TAG[$tag] ${message()}")
    }

    /** Warning level logging (message built eagerly). Prefer the lambda overload in hot paths. */
    fun w(tag: String, message: String) = w(tag) { message }

    /** Warning level logging (message built lazily). */
    inline fun w(tag: String, message: () -> String) {
        if (isLoggable(Level.WARN)) println("$APP_TAG[$tag] ${message()}")
    }

    /** Error level logging (message built eagerly). Prefer the lambda overload in hot paths. */
    fun e(tag: String, message: String) = e(tag) { message }

    /** Error level logging (message built lazily). */
    inline fun e(tag: String, message: () -> String) {
        if (isLoggable(Level.ERROR)) println("$APP_TAG[$tag] ${message()}")
    }

    /** Error level logging with throwable (message built eagerly). Prefer the lambda overload in hot paths. */
    fun e(tag: String, message: String, throwable: Throwable) = e(tag, throwable) { message }

    /** Error level logging with throwable (message built lazily). */
    inline fun e(tag: String, throwable: Throwable, message: () -> String) {
        if (isLoggable(Level.ERROR)) {
            println("$APP_TAG[$tag] ${message()}")
            throwable.printStackTrace()
        }
    }
}
