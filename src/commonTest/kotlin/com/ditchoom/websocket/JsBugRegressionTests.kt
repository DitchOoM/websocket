package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.WindowBits
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.compression.supportsStatefulFlush
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for two JS-specific bugs, exercised through the websocket's
 * actual compressSync/decompressToStringSync/decompressToBufferSync helpers.
 *
 * - JS_CONTEXT_TAKEOVER_BUG: flush() doesn't drain zlib Transform stream,
 *   causing message N-1 output to leak into message N.
 * - JS_WINDOWBITS_SIGN: custom windowBits (e.g. server_max_window_bits=9)
 *   causes decompression to fail silently.
 *
 * These tests are designed to FAIL before the fix and PASS after.
 */
class JsBugRegressionTests {
    private val factory = BufferFactory.managed()

    private fun stringToBuffer(s: String): ReadBuffer {
        val buf = factory.allocate(s.length * 3)
        buf.writeString(s, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    // =========================================================================
    // JS_CONTEXT_TAKEOVER_BUG: decompressToStringSync must not accumulate
    // =========================================================================

    /**
     * Exact reproduction from JS_CONTEXT_TAKEOVER_BUG.md using websocket helpers.
     *
     * Before fix: decompressToStringSync returns '{"msg":"hello"}{"msg":"world"}' (30 bytes)
     * After fix:  decompressToStringSync returns '{"msg":"world"}' (15 bytes)
     */
    @Test
    fun contextTakeover_decompressToString_noAccumulation() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferFactory.Default)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferFactory.Default)
        val decoder = StreamingStringDecoder()

        try {
            val msg0 = """{"msg":"hello"}"""
            val msg1 = """{"msg":"world"}"""

            // Message 0
            val compressed0 = compressSync(stringToBuffer(msg0), compressor)
            val combined0 = combineChunks(compressed0, factory)
            val result0 = decompressToStringSync(combined0, decompressor, decoder)
            assertEquals(msg0, result0, "msg0 content")

            // Message 1 — NO reset (context takeover)
            val compressed1 = compressSync(stringToBuffer(msg1), compressor)
            val combined1 = combineChunks(compressed1, factory)
            val result1 = decompressToStringSync(combined1, decompressor, decoder)

            assertEquals(
                msg1.length,
                result1.length,
                "msg1 length must be ${msg1.length}, not ${msg0.length + msg1.length} (accumulated)",
            )
            assertFalse(
                result1.startsWith(msg0),
                "msg1 must NOT start with msg0 — flush() leaked previous output",
            )
            assertEquals(msg1, result1, "msg1 content")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Same accumulation test using decompressToBufferSync (binary path).
     */
    @Test
    fun contextTakeover_decompressToBuffer_noAccumulation() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferFactory.Default)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferFactory.Default)

        try {
            val msg0 = """{"msg":"hello"}"""
            val msg1 = """{"msg":"world"}"""

            // Message 0
            val compressed0 = compressSync(stringToBuffer(msg0), compressor)
            val combined0 = combineChunks(compressed0, factory)
            val buf0 = decompressToBufferSync(combined0, decompressor, factory)
            assertEquals(msg0.length, buf0.remaining(), "msg0 buffer length")

            // Message 1 — NO reset (context takeover)
            val compressed1 = compressSync(stringToBuffer(msg1), compressor)
            val combined1 = combineChunks(compressed1, factory)
            val buf1 = decompressToBufferSync(combined1, decompressor, factory)

            assertEquals(
                msg1.length,
                buf1.remaining(),
                "msg1 buffer length must be ${msg1.length}, not ${msg0.length + msg1.length} (accumulated)",
            )
            val result1 = buf1.readString(buf1.remaining(), Charset.UTF8)
            assertEquals(msg1, result1, "msg1 buffer content")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Many sequential messages — checks no accumulation across 20 messages.
     */
    @Test
    fun contextTakeover_manyMessages_noAccumulation() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferFactory.Default)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferFactory.Default)
        val decoder = StreamingStringDecoder()
        var accumulatedLength = 0

        try {
            val messages = (0 until 20).map { i -> """{"seq":$i,"data":"${"X".repeat(50 + i * 10)}"}""" }
            for ((i, msg) in messages.withIndex()) {
                accumulatedLength += msg.length
                val compressed = compressSync(stringToBuffer(msg), compressor)
                val combined = combineChunks(compressed, factory)
                val result = decompressToStringSync(combined, decompressor, decoder)

                assertEquals(
                    msg.length,
                    result.length,
                    "Message $i: expected ${msg.length}, got ${result.length}. " +
                        "If $accumulatedLength, flush() is accumulating all prior messages.",
                )
                assertEquals(msg, result, "Message $i content")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // JS_WINDOWBITS_SIGN: custom windowBits must not cause silent failure
    // =========================================================================

    /**
     * Autobahn case 13.3.x: server compresses with windowBits=9, client
     * decompresses with default. Uses actual websocket helpers.
     *
     * Before fix: decompression fails silently → empty/wrong result
     * After fix:  round-trip succeeds
     */
    @Test
    fun windowBits9_websocketHelpers_roundTrip() {
        if (!supportsStatefulFlush) return
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                BufferFactory.Default,
                windowBits = WindowBits(9),
            )
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferFactory.Default)
        val decoder = StreamingStringDecoder()

        try {
            val msg = "Hello, WebSocket!"
            val compressed = compressSync(stringToBuffer(msg), compressor)
            assertTrue(totalRemaining(compressed) > 0, "Compressed output must not be empty")
            val combined = combineChunks(compressed, factory)
            val result = decompressToStringSync(combined, decompressor, decoder)
            assertEquals(msg, result, "windowBits=9 round-trip must succeed")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Negative windowBits normalization with websocket helpers.
     */
    @Test
    fun negativeWindowBits9_websocketHelpers_roundTrip() {
        if (!supportsStatefulFlush) return
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                BufferFactory.Default,
                windowBits = WindowBits(9),
            )
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferFactory.Default)
        val decoder = StreamingStringDecoder()

        try {
            val msg = "Negative windowBits test"
            val compressed = compressSync(stringToBuffer(msg), compressor)
            val combined = combineChunks(compressed, factory)
            val result = decompressToStringSync(combined, decompressor, decoder)
            assertEquals(msg, result, "Negative windowBits=-9 must normalize and round-trip")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Both bugs combined: windowBits=9 + context takeover + accumulation check.
     * This is the exact Autobahn 13.3.x failure scenario.
     */
    @Test
    fun windowBits9_contextTakeover_noAccumulation() {
        if (!supportsStatefulFlush) return
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                BufferFactory.Default,
                windowBits = WindowBits(9),
            )
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferFactory.Default)
        val decoder = StreamingStringDecoder()

        try {
            val msg0 = "Hello"
            val msg1 = "Hello" // identical — tests back-ref with small window

            val compressed0 = compressSync(stringToBuffer(msg0), compressor)
            val combined0 = combineChunks(compressed0, factory)
            val result0 = decompressToStringSync(combined0, decompressor, decoder)
            assertEquals(msg0, result0, "windowBits=9 msg0")

            val compressed1 = compressSync(stringToBuffer(msg1), compressor)
            val combined1 = combineChunks(compressed1, factory)
            val result1 = decompressToStringSync(combined1, decompressor, decoder)

            // Combined failure: result1 could be "HelloHello" (10 bytes) not "Hello" (5 bytes)
            assertEquals(
                msg1.length,
                result1.length,
                "windowBits=9 msg1: expected ${msg1.length}, got ${result1.length}. " +
                    "If ${msg0.length + msg1.length}, both bugs present.",
            )
            assertEquals(msg1, result1, "windowBits=9 msg1 content")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }
}
