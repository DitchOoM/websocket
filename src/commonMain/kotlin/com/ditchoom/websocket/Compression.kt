package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool

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

// Pre-allocated sync marker buffer to avoid allocation on every decompression.
// Uses Direct zone so that on Linux K/N, this is a NativeBuffer with direct pointer
// access. Heap zone would create a ByteArrayBuffer requiring pin/unpin (futex) on
// every withInputPointer() call in the zlib decompressor.
//
// IMPORTANT: Write individual bytes, not writeInt(). PlatformBuffer.allocate() defaults
// to ByteOrder.NATIVE which is LITTLE_ENDIAN on x86/ARM. writeInt(0x0000FFFF) on a
// little-endian buffer produces bytes FF FF 00 00 — wrong sync flush marker. The marker
// must be the exact byte sequence 00 00 FF FF regardless of platform byte order.
private val SYNC_FLUSH_MARKER_BUFFER: ReadBuffer by lazy {
    val buffer = PlatformBuffer.allocate(4)
    buffer.writeByte(0x00)
    buffer.writeByte(0x00)
    buffer.writeByte(0xFF.toByte())
    buffer.writeByte(0xFF.toByte())
    buffer.resetForRead()
    buffer
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
    compressor.compress(buffer) { chunks.add(it) }
    compressor.flush { chunks.add(it) }

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
    val combined = combineChunks(chunks, factory, pool)
    chunks.freeAll()
    return listOf(stripSyncFlushMarkerInPlace(combined))
}

/**
 * Decodes a decompressed chunk to StringBuilder and frees it.
 */
private fun decodeAndFree(
    chunk: ReadBuffer,
    decoder: StreamingStringDecoder,
    sb: StringBuilder,
) {
    if (chunk.position() != 0) chunk.position(0)
    if (chunk.remaining() > 0) {
        decoder.decode(chunk, sb)
    }
    chunk.freeIfNeeded()
}

/**
 * Decompresses a buffer directly to a String using a sync streaming decompressor.
 *
 * Uses [StreamingStringDecoder] for platform-optimized UTF-8 decoding with automatic
 * multi-byte boundary handling. Each decompressed chunk is decoded and freed immediately,
 * keeping peak memory to one chunk + StringBuilder.
 */
internal fun decompressToStringSync(
    buffer: ReadBuffer,
    decompressor: StreamingDecompressor,
    decoder: StreamingStringDecoder,
): String {
    val sb = StringBuilder()
    decompressor.decompress(buffer) { decodeAndFree(it, decoder, sb) }
    SYNC_FLUSH_MARKER_BUFFER.position(0)
    decompressor.decompress(SYNC_FLUSH_MARKER_BUFFER) { decodeAndFree(it, decoder, sb) }
    decompressor.flush { decodeAndFree(it, decoder, sb) }
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

    decompressor.decompress(buffer) { chunk -> addChunk(chunk) }

    SYNC_FLUSH_MARKER_BUFFER.position(0)
    decompressor.decompress(SYNC_FLUSH_MARKER_BUFFER) { chunk -> addChunk(chunk) }
    decompressor.flush { chunk -> addChunk(chunk) }

    // Single chunk optimization - no copy needed
    if (chunks.size == 1) {
        return chunks[0]
    }

    if (totalSize == 0) {
        chunks.freeAll()
        return ReadBuffer.EMPTY_BUFFER
    }

    // Combine chunks into final output
    val output = pool?.acquire(totalSize) ?: factory.allocate(totalSize)
    for (chunk in chunks) {
        chunk.position(0)
        output.write(chunk)
    }
    chunks.freeAll()
    output.resetForRead()
    return output
}
