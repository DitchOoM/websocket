package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.websocket.internal.writeRandomBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Generates a new random Sec-WebSocket-Key value (RFC 6455 §4.1).
 *
 * Produces 16 bytes of uniform randomness via [writeRandomBytes] and
 * base64-encodes them. The pre-v2 JVM and Android implementations used
 * `ByteArray(16) { Random.nextInt(97..122).toByte() }` — ASCII lowercase
 * only, which is less entropy than 16 random bytes and not what RFC 6455
 * calls for; this path now matches the Apple / JS / Linux implementations
 * (and the RFC) in producing 16 bytes of full-range randomness.
 *
 * Only the base64-encoding boundary still goes through a `ByteArray`
 * because `kotlin.io.encoding.Base64` takes one; 16 bytes once per
 * WebSocket handshake.
 */
@OptIn(ExperimentalEncodingApi::class)
fun generateWebSocketKey(): String {
    val buf = BufferFactory.managed().allocate(16)
    buf.writeRandomBytes(16)
    buf.resetForRead()
    @Suppress("NoByteArrayInProd") // kotlin.io.encoding.Base64 takes ByteArray
    val bytes = buf.readByteArray(16)
    return Base64.encode(bytes)
}
