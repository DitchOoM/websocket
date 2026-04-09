package com.ditchoom.websocket.benchmark

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.websocket.frame.FrameWriter
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/**
 * Benchmarks for the WebSocket compression pipeline.
 *
 * 1. Raw compress/decompress via streaming API (websocket-level: flush + marker strip)
 * 2. FrameWriter with compression (compress + frame header + masking)
 * 3. Full round-trip (compress via FrameWriter → decompress + UTF-8 decode)
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
class CompressedMessageBenchmark {
    private lateinit var compressor: StreamingCompressor
    private lateinit var decompressor: StreamingDecompressor
    private lateinit var decoder: StreamingStringDecoder

    private lateinit var compressedWriter: FrameWriter
    private lateinit var uncompressedWriter: FrameWriter

    // Test payloads
    private lateinit var smallText: String // 16B
    private lateinit var mediumText: String // 4KB
    private lateinit var largeText: String // 64KB

    // Pre-compressed payloads for decompression benchmarks (without marker — production format)
    private lateinit var smallCompressed: ReadBuffer
    private lateinit var mediumCompressed: ReadBuffer
    private lateinit var largeCompressed: ReadBuffer

    // Pre-compressed payloads WITH marker appended (for inline marker benchmark)
    private lateinit var smallCompressedWithMarker: ReadBuffer
    private lateinit var mediumCompressedWithMarker: ReadBuffer
    private lateinit var largeCompressedWithMarker: ReadBuffer

    companion object {
        private const val SMALL_SIZE = 16
        private const val MEDIUM_SIZE = 4 * 1024
        private const val LARGE_SIZE = 64 * 1024
        private const val SYNC_FLUSH_MARKER = 0x0000FFFF
    }

    @Setup
    fun setup() {
        compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        decoder = StreamingStringDecoder()

        compressedWriter = FrameWriter(
            compressor = StreamingCompressor.create(CompressionAlgorithm.Raw),
            compressionEnabled = true,
        )
        uncompressedWriter = FrameWriter()

        smallText = generateAsciiText(SMALL_SIZE)
        mediumText = generateAsciiText(MEDIUM_SIZE)
        largeText = generateAsciiText(LARGE_SIZE)

        // Pre-compress payloads for decompression benchmarks
        smallCompressed = compressForDecompBench(smallText)
        mediumCompressed = compressForDecompBench(mediumText)
        largeCompressed = compressForDecompBench(largeText)

        // Same but with marker appended (for inline marker benchmark)
        smallCompressedWithMarker = appendMarker(smallCompressed)
        mediumCompressedWithMarker = appendMarker(mediumCompressed)
        largeCompressedWithMarker = appendMarker(largeCompressed)
    }

    @TearDown
    fun teardown() {
        smallCompressed.freeIfNeeded()
        mediumCompressed.freeIfNeeded()
        largeCompressed.freeIfNeeded()
        smallCompressedWithMarker.freeIfNeeded()
        mediumCompressedWithMarker.freeIfNeeded()
        largeCompressedWithMarker.freeIfNeeded()
        compressor.close()
        decompressor.close()
    }

    // --- Compress benchmarks (streaming compressor + sync flush marker strip) ---

    @Benchmark
    fun compressSyncSmall(bh: Blackhole) {
        val buf = textToBuffer(smallText)
        val chunks = compressAndStripMarker(buf, compressor)
        bh.consume(chunks)
        chunks.freeAll()
        buf.freeIfNeeded()
        compressor.reset()
    }

    @Benchmark
    fun compressSyncMedium(bh: Blackhole) {
        val buf = textToBuffer(mediumText)
        val chunks = compressAndStripMarker(buf, compressor)
        bh.consume(chunks)
        chunks.freeAll()
        buf.freeIfNeeded()
        compressor.reset()
    }

    @Benchmark
    fun compressSyncLarge(bh: Blackhole) {
        val buf = textToBuffer(largeText)
        val chunks = compressAndStripMarker(buf, compressor)
        bh.consume(chunks)
        chunks.freeAll()
        buf.freeIfNeeded()
        compressor.reset()
    }

    // --- Decompress + UTF-8 decode benchmarks ---

    @Benchmark
    fun decompressToStringSyncSmall(bh: Blackhole) {
        smallCompressed.position(0)
        val str = decompressToString(smallCompressed, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    @Benchmark
    fun decompressToStringSyncMedium(bh: Blackhole) {
        mediumCompressed.position(0)
        val str = decompressToString(mediumCompressed, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    @Benchmark
    fun decompressToStringSyncLarge(bh: Blackhole) {
        largeCompressed.position(0)
        val str = decompressToString(largeCompressed, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    // --- FrameWriter with compression (compress + frame header + masking) ---

    @Benchmark
    fun writeTextFrameCompressedSmall(bh: Blackhole) {
        val frame = compressedWriter.writeTextFrame(smallText)
        bh.consume(frame)
        frame.freeIfNeeded()
    }

    @Benchmark
    fun writeTextFrameCompressedMedium(bh: Blackhole) {
        val frame = compressedWriter.writeTextFrame(mediumText)
        bh.consume(frame)
        frame.freeIfNeeded()
    }

    @Benchmark
    fun writeTextFrameCompressedLarge(bh: Blackhole) {
        val frame = compressedWriter.writeTextFrame(largeText)
        bh.consume(frame)
        frame.freeIfNeeded()
    }

    // --- FrameWriter without compression (baseline) ---

    @Benchmark
    fun writeTextFrameUncompressedMedium(bh: Blackhole) {
        val frame = uncompressedWriter.writeTextFrame(mediumText)
        bh.consume(frame)
        frame.freeIfNeeded()
    }

    // --- Decompress with inline marker (single zlib call) ---

    @Benchmark
    fun decompressInlineMarkerSmall(bh: Blackhole) {
        smallCompressedWithMarker.position(0)
        val str = decompressSingleCall(smallCompressedWithMarker, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    @Benchmark
    fun decompressInlineMarkerMedium(bh: Blackhole) {
        mediumCompressedWithMarker.position(0)
        val str = decompressSingleCall(mediumCompressedWithMarker, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    @Benchmark
    fun decompressInlineMarkerLarge(bh: Blackhole) {
        largeCompressedWithMarker.position(0)
        val str = decompressSingleCall(largeCompressedWithMarker, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    // --- Decompress with streaming string decode (no combineChunks) ---

    @Benchmark
    fun decompressStreamingDecodeMedium(bh: Blackhole) {
        mediumCompressed.position(0)
        val str = decompressToStringStreaming(mediumCompressed, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    @Benchmark
    fun decompressStreamingDecodeLarge(bh: Blackhole) {
        largeCompressed.position(0)
        val str = decompressToStringStreaming(largeCompressed, decompressor)
        bh.consume(str)
        decompressor.reset()
    }

    // --- Full round-trip: compress via FrameWriter → decompress + UTF-8 decode ---

    @Benchmark
    fun fullRoundTripSmall(bh: Blackhole) {
        val frame = compressedWriter.writeTextFrame(smallText)
        val compressed = extractCompressedPayload(frame)
        if (compressed != null) {
            val str = decompressToString(compressed, decompressor)
            bh.consume(str)
            compressed.freeIfNeeded()
            decompressor.reset()
        }
        frame.freeIfNeeded()
    }

    @Benchmark
    fun fullRoundTripMedium(bh: Blackhole) {
        val frame = compressedWriter.writeTextFrame(mediumText)
        val compressed = extractCompressedPayload(frame)
        if (compressed != null) {
            val str = decompressToString(compressed, decompressor)
            bh.consume(str)
            compressed.freeIfNeeded()
            decompressor.reset()
        }
        frame.freeIfNeeded()
    }

    // --- Helper functions ---

    private fun compressAndStripMarker(
        buffer: ReadBuffer,
        comp: StreamingCompressor,
    ): List<ReadBuffer> {
        val chunks = mutableListOf<ReadBuffer>()
        comp.compressUnsafe(buffer) { chunks.add(it) }
        comp.flushUnsafe { chunks.add(it) }

        if (chunks.isEmpty()) return emptyList()

        val lastChunk = chunks.last()
        if (lastChunk.remaining() >= 4) {
            val pos = lastChunk.position()
            val endPos = pos + lastChunk.remaining()
            lastChunk.position(endPos - 4)
            val marker = lastChunk.readInt()
            if (marker == SYNC_FLUSH_MARKER) {
                lastChunk.position(pos)
                lastChunk.setLimit(endPos - 4)
                if (lastChunk.remaining() == 0) {
                    chunks.removeLast()
                }
                return chunks
            }
            lastChunk.position(pos)
        }
        return chunks
    }

    private fun decompressToString(
        buffer: ReadBuffer,
        decomp: StreamingDecompressor,
    ): String {
        val sb = StringBuilder()

        decomp.decompressUnsafe(buffer) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }

        val marker = BufferFactory.Default.allocate(4)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decomp.decompressUnsafe(marker) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }
        marker.freeIfNeeded()

        decomp.finishUnsafe { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }

        decoder.finish(sb)
        decoder.reset()
        return sb.toString()
    }

    private fun extractCompressedPayload(frame: ReadBuffer): ReadBuffer? {
        if (frame.remaining() < 2) return null
        frame.position(0)

        val byte1 = frame.readByte().toInt() and 0xFF
        val byte2 = frame.readByte().toInt() and 0xFF
        val rsv1 = (byte1 and 0x40) != 0
        if (!rsv1) return null

        val masked = (byte2 and 0x80) != 0
        val len7 = byte2 and 0x7F
        val payloadLen = when (len7) {
            126 -> frame.readShort().toInt() and 0xFFFF
            127 -> frame.readLong().toInt()
            else -> len7
        }

        if (payloadLen == 0) return null

        val maskKey = if (masked) frame.readInt() else 0

        val payload = BufferFactory.Default.allocate(payloadLen)
        for (i in 0 until payloadLen) {
            payload.writeByte(frame.readByte())
        }
        payload.resetForRead()

        if (masked) {
            payload.xorMask(maskKey)
            payload.resetForRead()
        }

        return payload
    }

    private fun decompressSingleCall(
        bufferWithMarker: ReadBuffer,
        decomp: StreamingDecompressor,
    ): String {
        val sb = StringBuilder()
        decomp.decompressUnsafe(bufferWithMarker) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }
        decomp.flushUnsafe { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }

        decoder.finish(sb)
        decoder.reset()
        return sb.toString()
    }

    private fun decompressToStringStreaming(
        buffer: ReadBuffer,
        decomp: StreamingDecompressor,
    ): String {
        val sb = StringBuilder()

        decomp.decompressUnsafe(buffer) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }

        val marker = BufferFactory.Default.allocate(4)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decomp.decompressUnsafe(marker) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }
        marker.freeIfNeeded()

        decomp.flushUnsafe { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) decoder.decode(chunk, sb)
            chunk.freeIfNeeded()
        }

        decoder.finish(sb)
        decoder.reset()
        return sb.toString()
    }

    private fun appendMarker(compressed: ReadBuffer): ReadBuffer {
        val size = compressed.remaining()
        val withMarker = BufferFactory.Default.allocate(size + 4)
        compressed.position(0)
        withMarker.write(compressed)
        compressed.position(0)
        withMarker.writeInt(SYNC_FLUSH_MARKER)
        withMarker.resetForRead()
        return withMarker
    }

    private fun textToBuffer(text: String): ReadBuffer {
        val size = text.encodeToByteArray().size
        val buf = BufferFactory.Default.allocate(size)
        buf.writeString(text, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    private fun compressForDecompBench(text: String): ReadBuffer {
        val input = textToBuffer(text)
        val chunks = compressAndStripMarker(input, compressor)
        input.freeIfNeeded()
        compressor.reset()

        var totalSize = 0
        for (chunk in chunks) totalSize += chunk.remaining()
        val combined = BufferFactory.Default.allocate(totalSize)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        chunks.freeAll()
        combined.resetForRead()
        return combined
    }

    private fun generateAsciiText(targetBytes: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,;:!?-"
        val sb = StringBuilder(targetBytes)
        for (i in 0 until targetBytes) {
            sb.append(chars[i % chars.length])
        }
        return sb.toString()
    }
}
