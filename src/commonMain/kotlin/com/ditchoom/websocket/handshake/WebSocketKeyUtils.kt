package com.ditchoom.websocket.handshake

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Sha1
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.utf8ByteCount
import com.ditchoom.buffer.writeSha1Of
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.ditchoom.websocket.generateWebSocketKey as platformGenerateKey

// WebSocket key generation and validation utilities.
// Per RFC 6455 Section 4.1 and 4.2.2.

/**
 * The GUID used in Sec-WebSocket-Accept computation.
 *
 * Per RFC 6455 Section 4.2.2:
 * "The |Sec-WebSocket-Accept| header field indicates whether the server is
 * willing to accept the connection. [...] The server would use the
 * SHA-1 hash of the concatenation of the |Sec-WebSocket-Key| [...] with
 * the string '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'"
 */
const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/**
 * Generates a new random WebSocket key.
 *
 * Per RFC 6455 Section 4.1:
 * "The value of this header field MUST be a nonce consisting of a randomly
 * selected 16-byte value that has been base64-encoded"
 *
 * This delegates to platform-specific implementations.
 */
fun generateWebSocketKey(): String = platformGenerateKey()

/**
 * Computes the expected Sec-WebSocket-Accept value for a given client key.
 *
 * Per RFC 6455 Section 4.2.2:
 * 1. Take the Sec-WebSocket-Key header value (as a string).
 * 2. Append '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'.
 * 3. SHA-1 hash the resulting string.
 * 4. base64-encode the 20-byte hash.
 */
@OptIn(ExperimentalEncodingApi::class)
fun computeAcceptKey(clientKey: String): String {
    val concatenated = clientKey + WEBSOCKET_GUID
    val input = BufferFactory.managed().allocate(concatenated.utf8ByteCount())
    input.writeString(concatenated)
    input.resetForRead()

    val digest = BufferFactory.managed().allocate(Sha1.DIGEST_SIZE)
    digest.writeSha1Of(input)
    digest.resetForRead()

    // kotlin.io.encoding.Base64 takes ByteArray — one unavoidable boundary
    // copy at the stdlib API. This path runs once per WebSocket handshake.
    @Suppress("NoByteArrayInProd") // Kotlin stdlib Base64.encode takes ByteArray
    val digestBytes = digest.readByteArray(Sha1.DIGEST_SIZE)
    return Base64.encode(digestBytes)
}
