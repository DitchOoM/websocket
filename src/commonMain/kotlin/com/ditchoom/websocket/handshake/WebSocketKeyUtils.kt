package com.ditchoom.websocket.handshake

import com.ditchoom.websocket.generateWebSocketKey as platformGenerateKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
    val hash = sha1(concatenated.encodeToByteArray())
    return Base64.encode(hash)
}

// =============================================================================
// Pure-Kotlin SHA-1 (RFC 3174). Used only for the WebSocket accept key —
// no platform crypto dependency needed for this single fixed algorithm.
// =============================================================================

private fun sha1(input: ByteArray): ByteArray {
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()

    val bitLen = input.size.toLong() * 8
    // Padding: append 1 bit, zeros, then 64-bit length
    val padded = input.size + 1 + (63 - (input.size + 8) % 64) + 8
    val msg = ByteArray(padded)
    input.copyInto(msg)
    msg[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
        msg[msg.size - 1 - i] = (bitLen ushr (i * 8)).toByte()
    }

    val w = IntArray(80)

    for (offset in msg.indices step 64) {
        for (i in 0 until 16) {
            w[i] =
                ((msg[offset + i * 4].toInt() and 0xFF) shl 24) or
                    ((msg[offset + i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((msg[offset + i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (msg[offset + i * 4 + 3].toInt() and 0xFF)
        }
        for (i in 16 until 80) {
            w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        }

        var a = h0; var b = h1; var c = h2; var d = h3; var e = h4

        for (i in 0 until 80) {
            val (f, k) =
                when {
                    i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                    i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
        }

        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
    }

    val result = ByteArray(20)
    for (i in 0 until 4) {
        result[i] = (h0 ushr (24 - i * 8)).toByte()
        result[i + 4] = (h1 ushr (24 - i * 8)).toByte()
        result[i + 8] = (h2 ushr (24 - i * 8)).toByte()
        result[i + 12] = (h3 ushr (24 - i * 8)).toByte()
        result[i + 16] = (h4 ushr (24 - i * 8)).toByte()
    }
    return result
}
