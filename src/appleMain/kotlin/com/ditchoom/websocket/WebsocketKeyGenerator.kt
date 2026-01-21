package com.ditchoom.websocket

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.base64Encoding
import platform.Foundation.create
import kotlin.random.Random
import kotlin.random.nextInt

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun generateWebSocketKey(): String {
    val bytes = ByteArray(16) { Random.nextInt(97..122).toByte() }
    val nsData =
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    return nsData.base64Encoding()
}
