package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.StreamingStringDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Isolates the websocket compression path (compressSync → decompressToBufferSync)
 * to find the exact boundary where JS truncation occurs.
 */
class CompressionPathTest {
    private fun roundTrip(size: Int) {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        // Build payload
        val payload = BufferFactory.Default.allocate(size)
        repeat(size) { payload.writeByte((it % 251).toByte()) }
        payload.resetForRead()

        // Compress (websocket style: strips sync flush marker)
        val compressed = compressSync(payload, compressor)
        val compressedSize = totalRemaining(compressed)

        // Combine for decompression input
        val compressedBuf = combineChunks(compressed, BufferFactory.Default)
        compressed.freeAll()

        // Decompress (websocket style: re-appends sync flush marker internally)
        val decompressed = decompressToBufferSync(compressedBuf, decompressor, BufferFactory.Default)
        compressedBuf.freeIfNeeded()

        assertEquals(size, decompressed.remaining(), "Round-trip failed at size $size: compressed=$compressedSize, decompressed=${decompressed.remaining()}")

        // Verify content
        payload.position(0)
        for (i in 0 until size) {
            assertEquals(payload.readByte(), decompressed.readByte(), "Byte mismatch at offset $i for size $size")
        }

        decompressed.freeIfNeeded()
        compressor.close()
        decompressor.close()
    }

    private fun stringRoundTrip(size: Int) {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val decoder = StreamingStringDecoder()

        val text = "A".repeat(size)
        val payload = BufferFactory.Default.allocate(size)
        payload.writeString(text, Charset.UTF8)
        payload.resetForRead()

        val compressed = compressSync(payload, compressor)
        val compressedBuf = combineChunks(compressed, BufferFactory.Default)
        compressed.freeAll()

        val result = decompressToStringSync(compressedBuf, decompressor, decoder)
        compressedBuf.freeIfNeeded()

        assertEquals(size, result.length, "String round-trip failed at size $size")
        assertEquals(text, result)

        compressor.close()
        decompressor.close()
    }

    // Size sweep to find truncation boundary
    @Test fun roundTrip1KB() = roundTrip(1024)
    @Test fun roundTrip4KB() = roundTrip(4096)
    @Test fun roundTrip8KB() = roundTrip(8192)
    @Test fun roundTrip16KB() = roundTrip(16384)
    @Test fun roundTrip32KB() = roundTrip(32768)
    @Test fun roundTrip64KB() = roundTrip(65536)
    @Test fun roundTrip128KB() = roundTrip(131072)

    // String path (used for text messages)
    @Test fun stringRoundTrip32KB() = stringRoundTrip(32768)
    @Test fun stringRoundTrip64KB() = stringRoundTrip(65536)

    // Echo simulation: decompress then re-compress (like Autobahn cat-12)
    @Test
    fun echoRoundTrip16KB() = echoRoundTrip(16384)

    @Test
    fun echoRoundTrip32KB() = echoRoundTrip(32768)

    @Test
    fun echoRoundTrip24KB() = echoRoundTrip(24576)

    @Test
    fun echoRoundTrip28KB() = echoRoundTrip(28672)

    @Test
    fun echoRoundTrip30KB() = echoRoundTrip(30720)

    @Test
    fun echoRoundTrip31KB() = echoRoundTrip(31744)

    private fun echoRoundTrip(size: Int) {
        val serverCompressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val clientDecompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val clientCompressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        // Server compresses payload
        val original = BufferFactory.Default.allocate(size)
        repeat(size) { original.writeByte((it % 256).toByte()) }
        original.resetForRead()

        val serverCompressed = compressSync(original, serverCompressor)
        val serverCompressedBuf = combineChunks(serverCompressed, BufferFactory.Default)
        serverCompressed.freeAll()
        println("echoRoundTrip($size): server compressed ${original.remaining()} → ${serverCompressedBuf.remaining()} bytes")

        // Client decompresses
        val decompressed = decompressToBufferSync(serverCompressedBuf, clientDecompressor, BufferFactory.Default)
        serverCompressedBuf.freeIfNeeded()
        assertEquals(size, decompressed.remaining(), "Decompressed size mismatch at $size")

        // Client re-compresses for echo
        val clientCompressed = compressSync(decompressed, clientCompressor)
        val clientCompressedBuf = combineChunks(clientCompressed, BufferFactory.Default)
        clientCompressed.freeAll()
        println("echoRoundTrip($size): client re-compressed ${decompressed.remaining()} → ${clientCompressedBuf.remaining()} bytes")

        // Server decompresses echo — verify round-trip
        val serverDecompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val echoResult = decompressToBufferSync(clientCompressedBuf, serverDecompressor, BufferFactory.Default)
        clientCompressedBuf.freeIfNeeded()

        assertEquals(size, echoResult.remaining(), "Echo round-trip size mismatch at $size: got ${echoResult.remaining()}")

        // Verify content
        original.position(0)
        for (i in 0 until size) {
            assertEquals(original.readByte(), echoResult.readByte(), "Byte mismatch at offset $i for size $size")
        }

        decompressed.freeIfNeeded()
        echoResult.freeIfNeeded()
        serverCompressor.close()
        clientDecompressor.close()
        clientCompressor.close()
        serverDecompressor.close()
    }

    // Sequential (simulates echo of multiple messages)
    @Test
    fun sequential100Messages32KB() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        repeat(100) { i ->
            val payload = BufferFactory.Default.allocate(32768)
            repeat(32768) { payload.writeByte(((it + i) % 251).toByte()) }
            payload.resetForRead()

            val compressed = compressSync(payload, compressor)
            val compressedBuf = combineChunks(compressed, BufferFactory.Default)
            compressed.freeAll()

            val decompressed = decompressToBufferSync(compressedBuf, decompressor, BufferFactory.Default)
            compressedBuf.freeIfNeeded()

            assertEquals(32768, decompressed.remaining(), "Message $i: got ${decompressed.remaining()}")

            decompressed.freeIfNeeded()
            payload.freeIfNeeded()
            compressor.reset()
            decompressor.reset()
        }

        compressor.close()
        decompressor.close()
    }
}
