package com.ditchoom.websocket.internal

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlin.random.Random

/*
 * Buffer helpers that previously shipped from `com.ditchoom:buffer` (commits
 * 2d695d64 / 0685f12f / 21216f3f) and were withdrawn by the Phase 9 reset.
 * They live here as `internal` extensions so the websocket repo's handshake +
 * close-frame paths keep their zero-byte-array story without re-introducing
 * Phase 9's broader changes upstream. If buffer ever republishes them,
 * delete this file and switch the imports back.
 */

/**
 * Result of [truncateUtf8] — the (possibly-truncated) prefix of the input that fits in
 * the byte budget, plus the exact UTF-8 byte count of that prefix.
 *
 * Returning the byte count alongside the text avoids the second walk that a separate
 * `utf8ByteCount()` would force: the truncation walk already inspects every codepoint
 * to decide whether to include it, so the running byte total is essentially free.
 */
internal data class Utf8Truncation(
    val text: String,
    val byteSize: Int,
)

/**
 * Returns the longest codepoint-aligned prefix of this string whose UTF-8 encoding
 * fits within [maxBytes], plus that prefix's byte count.
 *
 * Single pass over the string: counts 1/2/3 bytes for BMP code points and 4 bytes for
 * surrogate pairs (RFC 3629). Stops including codepoints once the running byte total
 * would exceed [maxBytes]. Used by the WS Close-frame writer to enforce RFC 6455 §5.5
 * (control payload ≤ 125 bytes; minus 2 status bytes ⇒ ≤ 123 reason bytes) without a
 * pre-walk-then-encode pattern.
 *
 * Invalid UTF-16 (unpaired surrogate) is treated as the 3-byte U+FFFD replacement,
 * matching what a strict UTF-8 encoder would emit. Real WebSocket close reasons, HTTP
 * headers, and MQTT topics are always valid UTF-16, so this is a defensive default.
 */
internal fun String.truncateUtf8(maxBytes: Int): Utf8Truncation {
    if (maxBytes <= 0 || isEmpty()) return Utf8Truncation("", 0)
    var bytes = 0
    var i = 0
    val n = length
    while (i < n) {
        val c = this[i].code
        var advance = 1
        val cpBytes =
            when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF -> {
                    val next = if (i + 1 < n) this[i + 1].code else 0
                    if (next in 0xDC00..0xDFFF) {
                        advance = 2
                        4
                    } else {
                        3 // unpaired high surrogate → U+FFFD
                    }
                }
                c in 0xDC00..0xDFFF -> 3 // unpaired low surrogate → U+FFFD
                else -> 3
            }
        if (bytes + cpBytes > maxBytes) break
        bytes += cpBytes
        i += advance
    }
    val text = if (i == n) this else substring(0, i)
    return Utf8Truncation(text, bytes)
}

/**
 * Writes [count] uniformly-random bytes starting at this buffer's current
 * position, advancing position by [count].
 *
 * Backed by [Random.Default] — RFC 6455 §4.1 only requires the WebSocket
 * client masking key be "unpredictable from the server's perspective", not
 * CSPRNG-grade. Callers that need CSPRNG randomness should pass their own
 * [random].
 */
internal fun WriteBuffer.writeRandomBytes(
    count: Int,
    random: Random = Random.Default,
): WriteBuffer {
    require(count >= 0) { "count must be non-negative, was $count" }
    if (count == 0) return this

    val bulk = count and -8 // count rounded down to a multiple of 8
    var i = 0
    while (i < bulk) {
        val v = random.nextLong()
        writeByte((v ushr 0).toByte())
        writeByte((v ushr 8).toByte())
        writeByte((v ushr 16).toByte())
        writeByte((v ushr 24).toByte())
        writeByte((v ushr 32).toByte())
        writeByte((v ushr 40).toByte())
        writeByte((v ushr 48).toByte())
        writeByte((v ushr 56).toByte())
        i += 8
    }
    while (i < count) {
        writeByte(random.nextInt().toByte())
        i++
    }
    return this
}

/**
 * Pure-Kotlin streaming SHA-1 (RFC 3174) that reads from [ReadBuffer]s and
 * writes the 20-byte digest into a [WriteBuffer]. Used once per WebSocket
 * handshake (`Sec-WebSocket-Accept` computation, RFC 6455 §4.2.2). Cold-path
 * cost; pure Kotlin is fine at this call volume.
 *
 * Thread-unsafe. Reuse via [reset]. Scratch state is two fixed-size
 * [IntArray]s per instance (16 words for the current block, 80 for the
 * message schedule); no per-update allocation.
 */
internal class Sha1 {
    private var h0 = INITIAL_H0
    private var h1 = INITIAL_H1
    private var h2 = INITIAL_H2
    private var h3 = INITIAL_H3
    private var h4 = INITIAL_H4
    private val block = IntArray(16) // 16 big-endian words = 64-byte block
    private val w = IntArray(80) // SHA-1 message schedule (reused per block)
    private var byteIndexInBlock = 0
    private var totalBytes = 0L
    private var finished = false

    fun update(input: ReadBuffer): Sha1 {
        check(!finished) { "Sha1 already finished — call reset() before reusing" }
        val n = input.remaining()
        totalBytes += n
        var remaining = n
        while (remaining > 0) {
            val b = input.readByte().toInt() and 0xFF
            val wordIndex = byteIndexInBlock ushr 2
            val shift = 24 - ((byteIndexInBlock and 3) shl 3)
            block[wordIndex] = block[wordIndex] or (b shl shift)
            byteIndexInBlock++
            if (byteIndexInBlock == 64) {
                processBlock()
                byteIndexInBlock = 0
                block.fill(0)
            }
            remaining--
        }
        return this
    }

    fun finish(output: WriteBuffer): WriteBuffer {
        check(!finished) { "Sha1 already finished" }
        applyPaddingAndLength()
        finished = true
        writeWord(output, h0)
        writeWord(output, h1)
        writeWord(output, h2)
        writeWord(output, h3)
        writeWord(output, h4)
        return output
    }

    fun reset() {
        h0 = INITIAL_H0
        h1 = INITIAL_H1
        h2 = INITIAL_H2
        h3 = INITIAL_H3
        h4 = INITIAL_H4
        block.fill(0)
        byteIndexInBlock = 0
        totalBytes = 0L
        finished = false
    }

    private fun applyPaddingAndLength() {
        val bitLength = totalBytes * 8
        val wordIndex = byteIndexInBlock ushr 2
        val shift = 24 - ((byteIndexInBlock and 3) shl 3)
        block[wordIndex] = block[wordIndex] or (0x80 shl shift)
        byteIndexInBlock++

        if (byteIndexInBlock > 56) {
            processBlock()
            block.fill(0)
            byteIndexInBlock = 0
        }
        block[14] = (bitLength ushr 32).toInt()
        block[15] = bitLength.toInt()
        processBlock()
    }

    private fun processBlock() {
        for (i in 0 until 16) w[i] = block[i]
        for (i in 16 until 80) {
            w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4

        for (i in 0 until 80) {
            val f: Int
            val k: Int
            when {
                i < 20 -> {
                    f = (b and c) or (b.inv() and d)
                    k = K0
                }
                i < 40 -> {
                    f = b xor c xor d
                    k = K1
                }
                i < 60 -> {
                    f = (b and c) or (b and d) or (c and d)
                    k = K2
                }
                else -> {
                    f = b xor c xor d
                    k = K3
                }
            }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }

    private fun writeWord(
        output: WriteBuffer,
        word: Int,
    ) {
        output.writeByte((word ushr 24).toByte())
        output.writeByte((word ushr 16).toByte())
        output.writeByte((word ushr 8).toByte())
        output.writeByte(word.toByte())
    }

    companion object {
        const val DIGEST_SIZE: Int = 20
        private const val INITIAL_H0 = 0x67452301
        private val INITIAL_H1 = 0xEFCDAB89.toInt()
        private val INITIAL_H2 = 0x98BADCFE.toInt()
        private const val INITIAL_H3 = 0x10325476
        private val INITIAL_H4 = 0xC3D2E1F0.toInt()
        private const val K0 = 0x5A827999
        private val K1 = 0x6ED9EBA1
        private val K2 = 0x8F1BBCDC.toInt()
        private val K3 = 0xCA62C1D6.toInt()
    }
}

/**
 * One-shot convenience: hashes the remaining bytes of [input] and writes
 * the 20-byte digest at this buffer's current position. Consumes
 * `input.remaining()` bytes and advances this buffer by [Sha1.DIGEST_SIZE].
 */
internal fun WriteBuffer.writeSha1Of(input: ReadBuffer): WriteBuffer {
    val sha = Sha1()
    sha.update(input)
    return sha.finish(this)
}
