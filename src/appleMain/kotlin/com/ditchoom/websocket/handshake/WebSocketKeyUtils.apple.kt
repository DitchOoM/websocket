package com.ditchoom.websocket.handshake

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Apple implementation of Sec-WebSocket-Accept computation.
 *
 * Per RFC 6455 Section 4.2.2.
 *
 * Uses pure Kotlin SHA-1 to avoid cinterop number width issues
 * with CoreCrypto across Apple targets (arm64 vs x64).
 */
@OptIn(ExperimentalEncodingApi::class)
actual fun computeAcceptKey(clientKey: String): String {
    val concatenated = clientKey + WEBSOCKET_GUID
    val inputBytes = concatenated.encodeToByteArray()
    val hashBytes = sha1(inputBytes)
    return Base64.encode(hashBytes)
}

/**
 * Pure Kotlin SHA-1 implementation per RFC 3174.
 * Only used for WebSocket handshake - not for security-critical purposes.
 */
private fun sha1(message: ByteArray): ByteArray {
    // Initial hash values
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()

    // Pre-processing: adding padding bits
    val ml = message.size.toLong() * 8 // message length in bits
    val paddingLength = (56 - (message.size + 1) % 64 + 64) % 64
    val padded = ByteArray(message.size + 1 + paddingLength + 8)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    // Append length in bits as 64-bit big-endian
    for (i in 0..7) {
        padded[padded.size - 8 + i] = (ml shr (56 - i * 8)).toByte()
    }

    // Process each 512-bit chunk
    val w = IntArray(80)
    for (chunkStart in padded.indices step 64) {
        // Break chunk into sixteen 32-bit big-endian words
        for (i in 0..15) {
            w[i] = ((padded[chunkStart + i * 4].toInt() and 0xFF) shl 24) or
                ((padded[chunkStart + i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((padded[chunkStart + i * 4 + 2].toInt() and 0xFF) shl 8) or
                (padded[chunkStart + i * 4 + 3].toInt() and 0xFF)
        }

        // Extend the sixteen 32-bit words into eighty 32-bit words
        for (i in 16..79) {
            w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        }

        // Initialize working variables
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4

        // Main loop
        for (i in 0..79) {
            val (f, k) =
                when (i) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }

            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }

        // Add this chunk's hash to result
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }

    // Produce the final hash value (big-endian)
    return byteArrayOf(
        (h0 shr 24).toByte(),
        (h0 shr 16).toByte(),
        (h0 shr 8).toByte(),
        h0.toByte(),
        (h1 shr 24).toByte(),
        (h1 shr 16).toByte(),
        (h1 shr 8).toByte(),
        h1.toByte(),
        (h2 shr 24).toByte(),
        (h2 shr 16).toByte(),
        (h2 shr 8).toByte(),
        h2.toByte(),
        (h3 shr 24).toByte(),
        (h3 shr 16).toByte(),
        (h3 shr 8).toByte(),
        h3.toByte(),
        (h4 shr 24).toByte(),
        (h4 shr 16).toByte(),
        (h4 shr 8).toByte(),
        h4.toByte(),
    )
}

private fun Int.rotateLeft(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))
