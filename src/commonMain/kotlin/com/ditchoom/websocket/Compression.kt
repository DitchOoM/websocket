package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.decompressScoped
import com.ditchoom.buffer.compression.flushScoped
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool

/**
 * Compression configuration for a WebSocket codec connection.
 *
 * Models the result of permessage-deflate negotiation as a sealed type,
 * eliminating impossible states (e.g. context takeover flags without compression,
 * or compressionEnabled=true with null decompressor).
 */
sealed interface CompressionConfig {
    data object None : CompressionConfig

    /**
     * Compression was negotiated via permessage-deflate.
     *
     * @param decompressor Always present — incoming messages may be compressed.
     * @param compressor Null when the platform can't honor the negotiated client_max_window_bits
     *   (e.g. JVM Deflater only supports window=15). In that case, outgoing frames are uncompressed
     *   but incoming compressed frames are still decompressed.
     * @param serverNoContextTakeover If true, reset decompressor after each message.
     * @param clientNoContextTakeover If true, reset compressor after each message.
     */
    class Enabled(
        val decompressor: StreamingDecompressor,
        val compressor: StreamingCompressor?,
        val serverNoContextTakeover: Boolean,
        val clientNoContextTakeover: Boolean,
    ) : CompressionConfig
}

// WebSocket permessage-deflate terminator: 0x00 0x00 0xFF 0xFF

/**
 * Checks if the next 4 bytes at the buffer's current position are the sync flush marker.
 * Reads bytes individually to avoid byte order issues.
 * Does NOT advance the buffer position (restores it after reading).
 */
private fun isSyncFlushMarker(buffer: ReadBuffer): Boolean {
    if (buffer.remaining() < 4) return false
    val pos = buffer.position()
    val b0 = buffer.readByte()
    val b1 = buffer.readByte()
    val b2 = buffer.readByte()
    val b3 = buffer.readByte()
    buffer.position(pos)
    return b0 == 0x00.toByte() && b1 == 0x00.toByte() && b2 == 0xFF.toByte() && b3 == 0xFF.toByte()
}

// Pre-allocated sync marker: exact byte sequence 00 00 FF FF (RFC 7692).
// Wrapped from a constant byte array — no buffer allocation needed for 4 bytes.
private val SYNC_FLUSH_MARKER_BYTES = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
private val SYNC_FLUSH_MARKER_BUFFER: ReadBuffer by lazy {
    BufferFactory.managed().wrap(SYNC_FLUSH_MARKER_BYTES)
}

/**
 * Calculates total remaining bytes across a list of buffers.
 */
internal fun totalRemaining(chunks: List<ReadBuffer>): Int = chunks.sumOf { it.remaining() }

/**
 * Helper to combine multiple buffers into a single buffer.
 * When [pool] is provided, allocates from the pool for buffer reuse.
 */
internal fun combineChunks(
    chunks: List<ReadBuffer>,
    factory: BufferFactory = BufferFactory.managed(),
    pool: BufferPool? = null,
): ReadBuffer {
    if (chunks.isEmpty()) return ReadBuffer.EMPTY_BUFFER
    // Always copy to a new buffer. The single-chunk shortcut (returning chunks[0] directly)
    // causes use-after-free on Linux when callers do chunks.freeAll() after combining,
    // since combineChunks would have returned the same object that gets freed.
    val totalSize = chunks.sumOf { it.remaining() }
    if (totalSize == 0) return ReadBuffer.EMPTY_BUFFER
    val result = pool?.acquire(totalSize) ?: factory.allocate(totalSize)
    for (chunk in chunks) {
        result.write(chunk)
    }
    result.resetForRead()
    return result
}

/**
 * Strips the sync flush marker from a buffer by adjusting its limit.
 */
private fun stripSyncFlushMarkerInPlace(buffer: ReadBuffer): ReadBuffer {
    val remaining = buffer.remaining()
    if (remaining >= 4) {
        val pos = buffer.position()
        buffer.position(pos + remaining - 4)
        if (isSyncFlushMarker(buffer)) {
            buffer.position(pos)
            buffer.setLimit(buffer.limit() - 4)
        } else {
            buffer.position(pos)
        }
    }
    return buffer
}

/**
 * Compresses a buffer using a sync streaming compressor for WebSocket permessage-deflate.
 *
 * Uses the callback-based [StreamingCompressor] API directly to avoid
 * coroutine suspend/resume overhead (futex calls on Linux K/N).
 */
internal fun compressSync(
    buffer: ReadBuffer,
    compressor: StreamingCompressor,
    factory: BufferFactory = BufferFactory.managed(),
    pool: BufferPool? = null,
): List<ReadBuffer> {
    val chunks = mutableListOf<ReadBuffer>()
    try {
        compressor.compressUnsafe(buffer) { chunks.add(it) }
        compressor.flushUnsafe { chunks.add(it) }
    } catch (t: Throwable) {
        // Any chunks produced before the deflate/flush threw are orphaned —
        // release them before re-throwing so we don't leak direct memory.
        chunks.freeAll()
        throw t
    }

    if (chunks.isEmpty()) return emptyList()

    // Strip sync flush marker (4 bytes: 00 00 FF FF) from end
    // Try to do this without combining buffers
    val lastChunk = chunks.last()
    if (lastChunk.remaining() >= 4) {
        val pos = lastChunk.position()
        val endPos = pos + lastChunk.remaining()
        lastChunk.position(endPos - 4)
        if (isSyncFlushMarker(lastChunk)) {
            lastChunk.position(pos)
            lastChunk.setLimit(endPos - 4)
            if (lastChunk.remaining() == 0) {
                chunks.removeLast()
            }
            return chunks
        }
        lastChunk.position(pos)
    }

    // Marker spans multiple chunks or doesn't match - need to combine and strip
    val combined =
        try {
            combineChunks(chunks, factory, pool)
        } catch (t: Throwable) {
            chunks.freeAll()
            throw t
        }
    chunks.freeAll()
    return listOf(stripSyncFlushMarkerInPlace(combined))
}

/**
 * Decompresses a buffer directly to a String using a sync streaming decompressor.
 *
 * Uses [StreamingStringDecoder] for platform-optimized UTF-8 decoding with automatic
 * multi-byte boundary handling. Each decompressed chunk is decoded and freed immediately
 * via scoped API, keeping peak memory to one chunk + StringBuilder.
 */
internal fun decompressToStringSync(
    buffer: ReadBuffer,
    decompressor: StreamingDecompressor,
    decoder: StreamingStringDecoder,
): String {
    val sb = StringBuilder()
    decompressor.decompressScoped(buffer) { decoder.decode(this, sb) }
    SYNC_FLUSH_MARKER_BUFFER.position(0)
    decompressor.decompressScoped(SYNC_FLUSH_MARKER_BUFFER) { decoder.decode(this, sb) }
    decompressor.flushScoped { decoder.decode(this, sb) }
    decoder.finish(sb)
    decoder.reset()
    return sb.toString()
}

/**
 * Decompresses a buffer directly to a single output buffer using a sync streaming decompressor.
 */
internal fun decompressToBufferSync(
    buffer: ReadBuffer,
    decompressor: StreamingDecompressor,
    factory: BufferFactory = BufferFactory.managed(),
    pool: BufferPool? = null,
): ReadBuffer {
    var totalSize = 0
    val chunks = mutableListOf<ReadBuffer>()

    fun addChunk(chunk: ReadBuffer) {
        if (chunk.position() != 0) {
            chunk.position(0)
        }
        totalSize += chunk.remaining()
        chunks.add(chunk)
    }

    try {
        decompressor.decompressUnsafe(buffer) { chunk -> addChunk(chunk) }

        SYNC_FLUSH_MARKER_BUFFER.position(0)
        decompressor.decompressUnsafe(SYNC_FLUSH_MARKER_BUFFER) { chunk -> addChunk(chunk) }
        decompressor.flushUnsafe { chunk -> addChunk(chunk) }
    } catch (t: Throwable) {
        chunks.freeAll()
        throw t
    }

    // Single chunk optimization - no copy needed
    if (chunks.size == 1) {
        return chunks[0]
    }

    if (totalSize == 0) {
        chunks.freeAll()
        return ReadBuffer.EMPTY_BUFFER
    }

    // Combine chunks into final output. Allocation can throw (e.g. OOM) —
    // release the staged chunks before propagating so the leak doesn't
    // compound on the exception path.
    val output =
        try {
            pool?.acquire(totalSize) ?: factory.allocate(totalSize)
        } catch (t: Throwable) {
            chunks.freeAll()
            throw t
        }
    try {
        for (chunk in chunks) {
            chunk.position(0)
            output.write(chunk)
        }
    } catch (t: Throwable) {
        output.freeIfNeeded()
        chunks.freeAll()
        throw t
    }
    chunks.freeAll()
    output.resetForRead()
    return output
}
