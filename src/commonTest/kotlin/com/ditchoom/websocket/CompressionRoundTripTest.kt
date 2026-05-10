package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.BufferAllocator
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Isolates the WebSocket compression round-trip to find where JS diverges.
 *
 * Tests each layer independently:
 * 1. Compress → decompress (buffer-compression only, no websocket)
 * 2. compressSync → decompressToStringSync (websocket compression helpers)
 * 3. Same with custom windowBits
 * 4. Same with context takeover (multiple messages, no reset)
 */
class CompressionRoundTripTest {
    private val factory = BufferFactory.managed()
    private val allocator = BufferAllocator.Default

    private fun stringToBuffer(s: String): ReadBuffer {
        val buf = factory.allocate(s.length * 3)
        buf.writeString(s, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    // ========================================================================
    // Layer 1: Raw buffer-compression round-trip (no websocket code)
    // ========================================================================

    @Test
    fun rawCompressDecompress_defaultWindowBits() {
        val input = "Hello, WebSocket compression!"
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferAllocator.Default)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)

        // Compress
        val compressed = mutableListOf<ReadBuffer>()
        compressor.compress(stringToBuffer(input)) { compressed.add(it) }
        compressor.flush { compressed.add(it) }

        // Decompress (append sync flush marker as permessage-deflate requires)
        val sb = StringBuilder()
        val decoder = StreamingStringDecoder()
        for (chunk in compressed) {
            chunk.position(0)
            decompressor.decompress(chunk) { out ->
                if (out.position() != 0) out.position(0)
                decoder.decode(out, sb)
            }
        }
        val marker = factory.wrap(byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))
        decompressor.decompress(marker) { out ->
            if (out.position() != 0) out.position(0)
            decoder.decode(out, sb)
        }
        decompressor.flush { out ->
            if (out.position() != 0) out.position(0)
            decoder.decode(out, sb)
        }
        decoder.finish(sb)

        assertEquals(input, sb.toString())
        compressor.close()
        decompressor.close()
    }

    @Test
    fun rawCompressDecompress_windowBits9() {
        val input = "Hello, WebSocket compression with windowBits=9!"
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                bufferFactory,
                windowBits = -9, // negative = raw deflate convention (websocket passes this)
            )
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)

        val compressed = mutableListOf<ReadBuffer>()
        compressor.compress(stringToBuffer(input)) { compressed.add(it) }
        compressor.flush { compressed.add(it) }

        val sb = StringBuilder()
        val decoder = StreamingStringDecoder()
        for (chunk in compressed) {
            chunk.position(0)
            decompressor.decompress(chunk) { out ->
                if (out.position() != 0) out.position(0)
                decoder.decode(out, sb)
            }
        }
        val marker = factory.wrap(byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))
        decompressor.decompress(marker) { out ->
            if (out.position() != 0) out.position(0)
            decoder.decode(out, sb)
        }
        decompressor.flush { out ->
            if (out.position() != 0) out.position(0)
            decoder.decode(out, sb)
        }
        decoder.finish(sb)

        assertEquals(input, sb.toString())
        compressor.close()
        decompressor.close()
    }

    // ========================================================================
    // Layer 2: WebSocket compression helpers (compressSync / decompressToStringSync)
    // ========================================================================

    @Test
    fun websocketCompressDecompress_defaultWindowBits() {
        val input = "Hello, WebSocket compression!"
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferAllocator.Default)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)

        // Compress using websocket helper (strips sync flush marker)
        val compressed = compressSync(stringToBuffer(input), compressor)
        val compressedSize = totalRemaining(compressed)
        println("  compressed: $compressedSize bytes from ${input.length} chars")

        // Decompress using websocket helper (appends sync flush marker)
        val decoder = StreamingStringDecoder()
        val combined = combineChunks(compressed, factory)
        val result = decompressToStringSync(combined, decompressor, decoder)

        assertEquals(input, result)
        compressor.close()
        decompressor.close()
    }

    @Test
    fun websocketCompressDecompress_windowBits9() {
        val input = "Hello, WebSocket compression with windowBits=9!"
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                bufferFactory,
                windowBits = -9,
            )
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)

        val compressed = compressSync(stringToBuffer(input), compressor)
        val compressedSize = totalRemaining(compressed)
        println("  compressed (wb=9): $compressedSize bytes from ${input.length} chars")

        val decoder = StreamingStringDecoder()
        val combined = combineChunks(compressed, factory)
        val result = decompressToStringSync(combined, decompressor, decoder)

        assertEquals(input, result)
        compressor.close()
        decompressor.close()
    }

    // ========================================================================
    // Layer 3: Context takeover (multiple messages, no reset between)
    // ========================================================================

    @Test
    fun websocketContextTakeover_defaultWindowBits_msg0() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferAllocator.Default)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)
        roundTripMessage(compressor, decompressor, """{"msg":"hello"}""", "msg0")
        compressor.close()
        decompressor.close()
    }

    @Test
    fun websocketContextTakeover_defaultWindowBits_msg1() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferAllocator.Default)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)
        // First message (sets up context)
        roundTripMessage(compressor, decompressor, """{"msg":"hello"}""", "setup")
        // Second message (uses context takeover)
        roundTripMessage(compressor, decompressor, """{"msg":"world"}""", "msg1_with_context")
        compressor.close()
        decompressor.close()
    }

    private fun roundTripMessage(
        compressor: StreamingCompressor,
        decompressor: StreamingDecompressor,
        input: String,
        label: String,
    ) {
        val compressed = compressSync(stringToBuffer(input), compressor)
        val compressedSize = totalRemaining(compressed)
        val combined = combineChunks(compressed, factory)
        val decoder = StreamingStringDecoder()
        val result = decompressToStringSync(combined, decompressor, decoder)
        assertEquals(
            input.length,
            result.length,
            "$label: length mismatch: expected=${input.length} actual=${result.length} " +
                "compressed=$compressedSize input='$input' result='${result.take(50)}'",
        )
        assertEquals(input, result, "$label: content mismatch")
    }

    @Test
    fun websocketContextTakeover_windowBits9() {
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                bufferFactory,
                windowBits = -9,
            )
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)
        val decoder = StreamingStringDecoder()

        val messages =
            listOf(
                """{"msg":"hello"}""",
                """{"msg":"world"}""",
                """{"msg":"hello"}""",
            )

        for ((i, msg) in messages.withIndex()) {
            val compressed = compressSync(stringToBuffer(msg), compressor)
            val compressedSize = totalRemaining(compressed)
            println("  msg[$i] wb9 compressed: $compressedSize bytes from ${msg.length} chars")

            val combined = combineChunks(compressed, factory)
            val result = decompressToStringSync(combined, decompressor, decoder)
            assertEquals(msg, result, "Message $i round-trip failed with windowBits=9")
        }

        compressor.close()
        decompressor.close()
    }

    // ========================================================================
    // Layer 4: Simulate Autobahn 13.3.1 exactly
    // ========================================================================

    @Test
    fun simulateAutobahn13_3_1() {
        // Autobahn 13.3.1: server_max_window_bits=9, context takeover,
        // 1000 messages of 16 bytes each
        // Client compresses with windowBits=9 (negotiated client_max_window_bits=9)
        val compressor =
            StreamingCompressor.create(
                CompressionAlgorithm.Raw,
                CompressionLevel.Default,
                bufferFactory,
                windowBits = -9,
            )
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferAllocator.Default)
        val decoder = StreamingStringDecoder()

        // Simulate 10 echo round-trips (enough to detect issues)
        repeat(10) { i ->
            val msg = """{"AutobahnPy""" // 16 bytes, similar to Autobahn payload
            val padded = msg.padEnd(16, '}')

            // Compress (simulates what server sends)
            val compressed = compressSync(stringToBuffer(padded), compressor)
            val compressedSize = totalRemaining(compressed)

            // Decompress (simulates what client receives)
            val combined = combineChunks(compressed, factory)
            val result = decompressToStringSync(combined, decompressor, decoder)

            assertEquals(padded.length, result.length, "Message $i: length mismatch")
            assertEquals(padded, result, "Message $i: content mismatch")

            if (i < 3 || i == 9) {
                println("  autobahn[$i]: compressed=$compressedSize bytes, decompressed=${result.length} chars")
            }
        }

        compressor.close()
        decompressor.close()
    }
}
