package com.ditchoom.websocket

import com.ditchoom.buffer.DataBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.wrap
import platform.Foundation.base64Encoding
import kotlin.random.Random
import kotlin.random.nextInt

actual fun generateWebSocketKey(): String {
    val bytes = ByteArray(16) { Random.nextInt(97..122).toByte() }
    return (PlatformBuffer.wrap(bytes) as DataBuffer).data.base64Encoding()
}
