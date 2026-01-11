package com.ratulsarna.ocmobile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
fun dataToKotlinByteArray(data: NSData): ByteArray {
    val size = data.length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun kotlinByteArrayToData(bytes: ByteArray): NSData {
    if (bytes.isEmpty()) {
        return NSData.create(bytes = null, length = 0u)
    }
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}
