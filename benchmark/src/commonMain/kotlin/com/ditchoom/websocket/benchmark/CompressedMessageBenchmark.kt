package com.ditchoom.websocket.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
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
 * Isolates each layer to identify where LinuxX64 is slower than JVM:
 * 1. Raw compress/decompress via streaming API (websocket-level: flush + marker strip)
 * 2. FrameWriter with compression (compress + frame header + masking)
 * 3. Full round-trip (compress via FrameWriter → decompress + UTF-8 decode)
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
class CompressedMessageBenchmark {
    // Streaming compressor/decompressor (public API from buffer-compression)
    private lateinit var compressor: StreamingCompressor
    private lateinit var decompressor: StreamingDecompressor

    // Buffer pool matching production ModularWebSocketClient pattern
    private lateinit var pool: BufferPool

    // FrameWriter with compression enabled
    private lateinit var compressedWriter: FrameWriter

    // FrameWriter without compression (baseline)
    private lateinit var uncompressedWriter: FrameWriter

    // Test payloads
    private lateinit var smallText: String // 16B
    private lateinit var text256: String // 256B
    private lateinit var text1k: String // 1KB
    private lateinit var mediumText: String // 4KB
    private lateinit var text16k: String // 16KB
    private lateinit var largeText: String // 64KB

    // Large payloads allocated lazily to avoid OOMing JS
    private val text1m: String by lazy { generateAsciiText(1024 * 1024) }
    private val text16m: String by lazy { generateAsciiText(16 * 1024 * 1024) }

    // Pre-compressed payloads for decompression benchmarks
    private lateinit var smallCompressed: ReadBuffer
    private lateinit var mediumCompressed: ReadBuffer
    private lateinit var largeCompressed: ReadBuffer

    // Pre-allocated buffers for xorMask isolation benchmarks
    private lateinit var xorMaskBuf: ReadWriteBuffer
    private lateinit var xorMaskCopySrc: ReadWriteBuffer // pre-populated source for pure xorMaskCopy

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
        pool = BufferPool()

        compressedWriter = FrameWriter(
            compressor = StreamingCompressor.create(CompressionAlgorithm.Raw),
            compressionEnabled = true,
            pool = pool,
        )
        uncompressedWriter = FrameWriter(pool = pool)

        // Generate ASCII test strings at each size
        smallText = generateAsciiText(SMALL_SIZE)
        text256 = generateAsciiText(256)
        text1k = generateAsciiText(1024)
        mediumText = generateAsciiText(MEDIUM_SIZE)
        text16k = generateAsciiText(16 * 1024)
        largeText = generateAsciiText(LARGE_SIZE)

        // Pre-allocate buffer for xorMask benchmark
        xorMaskBuf = PlatformBuffer.allocate(MEDIUM_SIZE, AllocationZone.Direct) as ReadWriteBuffer
        repeat(MEDIUM_SIZE) { xorMaskBuf.writeByte(it.toByte()) }

        // Pre-populated 4KB source for pure xorMaskCopy benchmark (no writeString cost)
        xorMaskCopySrc = PlatformBuffer.allocate(MEDIUM_SIZE, AllocationZone.Direct) as ReadWriteBuffer
        xorMaskCopySrc.writeString(mediumText, Charset.UTF8)
        xorMaskCopySrc.resetForRead()

        // Pre-compress payloads for decompression benchmarks
        smallCompressed = compressForDecompBench(smallText)
        mediumCompressed = compressForDecompBench(mediumText)
        largeCompressed = compressForDecompBench(largeText)
    }

    @TearDown
    fun teardown() {
        smallCompressed.freeIfNeeded()
        mediumCompressed.freeIfNeeded()
        largeCompressed.freeIfNeeded()
        xorMaskBuf.freeIfNeeded()
        xorMaskCopySrc.freeIfNeeded()
        compressor.close()
        decompressor.close()
        pool.clear()
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
        smallCompressed.position(0) // rewind, not flip (buffer already in read mode)
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

    // --- Isolated writeString benchmarks (no frame overhead) ---

    @Benchmark
    fun writeStringSmall(bh: Blackhole) {
        val buf = pool.acquire()
        buf.writeString(smallText, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    @Benchmark
    fun writeString256B(bh: Blackhole) {
        val buf = pool.acquire()
        buf.writeString(text256, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    @Benchmark
    fun writeString1KB(bh: Blackhole) {
        val buf = pool.acquire()
        buf.writeString(text1k, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    @Benchmark
    fun writeStringMedium(bh: Blackhole) {
        val buf = pool.acquire()
        buf.writeString(mediumText, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    @Benchmark
    fun writeString16KB(bh: Blackhole) {
        val buf = pool.acquire()
        buf.writeString(text16k, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    @Benchmark
    fun writeStringLarge(bh: Blackhole) {
        val buf = pool.acquire()
        buf.writeString(largeText, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    @Benchmark
    fun writeString1MB(bh: Blackhole) {
        val buf = pool.acquire(1024 * 1024)
        buf.writeString(text1m, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    @Benchmark
    fun writeString16MB(bh: Blackhole) {
        val buf = pool.acquire(16 * 1024 * 1024)
        buf.writeString(text16m, Charset.UTF8)
        bh.consume(buf)
        pool.release(buf)
    }

    // --- FrameWriter component isolation benchmarks (4KB) ---

    @Benchmark
    fun utf8ByteSizeMedium(bh: Blackhole) {
        bh.consume(utf8ByteSize(mediumText))
    }

    @Benchmark
    fun randomNextInt(bh: Blackhole) {
        bh.consume(kotlin.random.Random.nextInt())
    }

    @Benchmark
    fun xorMaskMedium(bh: Blackhole) {
        // Mask in-place and unmask to restore for next iteration
        xorMaskBuf.position(0)
        xorMaskBuf.setLimit(MEDIUM_SIZE)
        xorMaskBuf.xorMask(0x12345678)
        bh.consume(xorMaskBuf)
        xorMaskBuf.position(0)
        xorMaskBuf.setLimit(MEDIUM_SIZE)
        xorMaskBuf.xorMask(0x12345678)
    }

    @Benchmark
    fun xorMaskCopyMedium(bh: Blackhole) {
        // Includes writeString + pool + xorMaskCopy (matches full writeTextFrame pipeline)
        val src = pool.acquire(MEDIUM_SIZE)
        src.writeString(mediumText, Charset.UTF8)
        src.resetForRead()
        // Dest has 8-byte header offset (2 header + 2 ext len + 4 mask) to match real frames
        val dst = pool.acquire(MEDIUM_SIZE + 8)
        dst.position(8)
        dst.xorMaskCopy(src, 0x12345678)
        bh.consume(dst)
        pool.release(src)
        pool.release(dst)
    }

    @Benchmark
    fun xorMaskCopyPureMedium(bh: Blackhole) {
        // Pure copy+mask only — pre-populated source, no writeString cost
        xorMaskCopySrc.position(0)
        val dst = pool.acquire(MEDIUM_SIZE + 8)
        dst.position(8)
        dst.xorMaskCopy(xorMaskCopySrc, 0x12345678)
        bh.consume(dst)
        pool.release(dst)
    }

    @Benchmark
    fun poolAcquireReleaseMedium(bh: Blackhole) {
        val buf = pool.acquire(MEDIUM_SIZE + 8)
        bh.consume(buf)
        pool.release(buf)
    }

    // --- Full round-trip: compress via FrameWriter → decompress + UTF-8 decode ---

    @Benchmark
    fun fullRoundTripSmall(bh: Blackhole) {
        val frame = compressedWriter.writeTextFrame(smallText)
        // Extract compressed payload from frame (skip header + mask)
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

    /**
     * Reproduces the websocket compress pipeline using public StreamingCompressor API:
     * compress → flush → strip sync flush marker (0x00 0x00 0xFF 0xFF)
     */
    private fun compressAndStripMarker(
        buffer: ReadBuffer,
        comp: StreamingCompressor,
    ): List<ReadBuffer> {
        val chunks = mutableListOf<ReadBuffer>()
        comp.compress(buffer) { chunks.add(it) }
        comp.flush { chunks.add(it) }

        if (chunks.isEmpty()) return emptyList()

        // Strip sync flush marker from end (same logic as compressSync)
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

    /**
     * Reproduces the websocket decompress-to-string pipeline using public StreamingDecompressor API:
     * decompress → append sync flush marker → finish → readString
     */
    private fun decompressToString(
        buffer: ReadBuffer,
        decomp: StreamingDecompressor,
    ): String {
        val chunks = mutableListOf<ReadBuffer>()

        decomp.decompress(buffer) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

        // Append sync marker and decompress (flushes pending output)
        val marker = PlatformBuffer.allocate(4, AllocationZone.Direct)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decomp.decompress(marker) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }
        marker.freeIfNeeded()

        decomp.finish { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

        if (chunks.isEmpty()) return ""

        // Single chunk fast path
        if (chunks.size == 1) {
            val chunk = chunks[0]
            val result = chunk.readString(chunk.remaining(), Charset.UTF8)
            chunk.freeIfNeeded()
            return result
        }

        // Combine chunks and decode
        var totalSize = 0
        for (chunk in chunks) totalSize += chunk.remaining()
        val combined = PlatformBuffer.allocate(totalSize, AllocationZone.Heap)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        chunks.freeAll()
        combined.resetForRead()
        val result = combined.readString(totalSize, Charset.UTF8)
        combined.freeIfNeeded()
        return result
    }

    /**
     * Extracts the unmasked compressed payload from a WebSocket frame.
     * Frame format: [header 2B] [ext len 0/2/8B] [mask 4B] [masked payload]
     */
    private fun extractCompressedPayload(frame: ReadBuffer): ReadBuffer? {
        if (frame.remaining() < 2) return null
        frame.position(0)

        val byte1 = frame.readByte().toInt() and 0xFF
        val byte2 = frame.readByte().toInt() and 0xFF
        val rsv1 = (byte1 and 0x40) != 0
        if (!rsv1) return null // Not compressed

        val masked = (byte2 and 0x80) != 0
        val len7 = byte2 and 0x7F
        val payloadLen = when (len7) {
            126 -> frame.readShort().toInt() and 0xFFFF
            127 -> frame.readLong().toInt()
            else -> len7
        }

        if (payloadLen == 0) return null

        val maskKey = if (masked) frame.readInt() else 0

        // Read payload and unmask
        val payload = PlatformBuffer.allocate(payloadLen, AllocationZone.Direct)
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

    private fun textToBuffer(text: String): ReadBuffer {
        val size = text.encodeToByteArray().size
        val buf = PlatformBuffer.allocate(size, AllocationZone.Direct)
        buf.writeString(text, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    /**
     * Creates a pre-compressed buffer for decompression benchmarks.
     * Compresses with sync flush and strips the marker (websocket format).
     */
    private fun compressForDecompBench(text: String): ReadBuffer {
        val input = textToBuffer(text)
        val chunks = compressAndStripMarker(input, compressor)
        input.freeIfNeeded()
        compressor.reset()

        // Combine into single buffer for consistent benchmark input
        var totalSize = 0
        for (chunk in chunks) totalSize += chunk.remaining()
        val combined = PlatformBuffer.allocate(totalSize, AllocationZone.Direct)
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

    /** Same logic as internal Utf8.byteSize — measures character iteration cost */
    private fun utf8ByteSize(text: String): Int {
        var size = 0
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i].code
            when {
                c <= 0x7F -> { size += 1; i++ }
                c <= 0x7FF -> { size += 2; i++ }
                c in 0xD800..0xDBFF && i + 1 < len && text[i + 1].code in 0xDC00..0xDFFF -> { size += 4; i += 2 }
                else -> { size += 3; i++ }
            }
        }
        return size
    }
}
