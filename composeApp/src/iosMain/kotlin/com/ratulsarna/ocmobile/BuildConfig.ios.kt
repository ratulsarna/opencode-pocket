package com.ratulsarna.ocmobile

actual object BuildConfig {
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    actual val isDebug: Boolean = kotlin.native.Platform.isDebugBinary
}
