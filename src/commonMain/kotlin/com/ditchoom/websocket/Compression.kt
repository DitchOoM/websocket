package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.StreamingStringDecoder
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.SuspendingStreamingCompressor
import com.ditchoom.buffer.compression.SuspendingStreamingDecompressor
import com.ditchoom.buffer.compression.compressAsync
import com.ditchoom.buffer.compression.decompressAsync
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool

// WebSocket permessage-deflate terminator: 0x00 0x00 0xFF 0xFF
private const val SYNC_FLUSH_MARKER = 0x0000FFFF

// Pre-allocated sync marker buffer to avoid allocation on every decompression.
// Uses Direct zone so that on Linux K/N, this is a NativeBuffer with direct pointer
// access. Heap zone would create a ByteArrayBuffer requiring pin/unpin (futex) on
// every withInputPointer() call in the zlib decompressor.
private val SYNC_FLUSH_MARKER_BUFFER: ReadBuffer by lazy {
    val buffer = PlatformBuffer.allocate(4, AllocationZone.Direct)
    buffer.writeInt(SYNC_FLUSH_MARKER)
    buffer.resetForRead()
    buffer
}

/**
 * Decompresses a WebSocket permessage-deflate compressed buffer.
 *
 * WebSocket permessage-deflate strips the Z_SYNC_FLUSH marker before transmission,
 * so this function appends it back before decompression.
 */
suspend fun ReadBuffer.decompressWebsocketBuffer(zone: AllocationZone = AllocationZone.Direct): ReadBuffer {
    // WebSocket permessage-deflate requires appending the terminator before decompression
    val wrappedBuffer = PlatformBuffer.allocate(this.remaining() + 4, zone)
    wrappedBuffer.write(this)
    wrappedBuffer.writeInt(SYNC_FLUSH_MARKER)
    wrappedBuffer.resetForRead()
    val result = decompressAsync(wrappedBuffer, CompressionAlgorithm.Raw, zone)
    wrappedBuffer.closeIfNeeded() // Free temp native buffer
    return result
}

/**
 * Compresses a buffer for WebSocket permessage-deflate transmission.
 *
 * Uses Z_SYNC_FLUSH to produce independently decompressible data, then strips
 * the trailing sync marker as required by the permessage-deflate extension.
 *
 * @param level Compression level: -1 for default, 0-9 for specific levels
 * @param zone Allocation zone for the output buffer
 */
suspend fun ReadBuffer.compressWebsocketBuffer(
    level: Int = -1,
    zone: AllocationZone = AllocationZone.Heap,
): ReadBuffer {
    val compressionLevel =
        when {
            level < 0 -> CompressionLevel.Default
            level == 0 -> CompressionLevel.NoCompression
            level == 1 -> CompressionLevel.BestSpeed
            level == 9 -> CompressionLevel.BestCompression
            else -> CompressionLevel.Custom(level.coerceIn(0, 9))
        }
    val compressed = compressAsync(this, CompressionAlgorithm.Raw, compressionLevel, zone)
    // WebSocket permessage-deflate requires stripping the terminator if present
    if (compressed.remaining() >= 4) {
        val positionLastFourBytes = compressed.limit() - 4
        val last4Bytes = compressed.getInt(positionLastFourBytes)
        if (last4Bytes == SYNC_FLUSH_MARKER) {
            compressed.setLimit(compressed.limit() - 4)
        }
    }
    return compressed
}

/**
 * Compresses a buffer using a streaming compressor for WebSocket permessage-deflate.
 *
 * Uses the provided stateful compressor to compress data with Z_SYNC_FLUSH,
 * then strips the trailing sync marker as required by permessage-deflate.
 * Minimizes buffer copies by adjusting the last chunk's limit when possible.
 *
 * @param buffer The input buffer to compress
 * @param compressor The streaming compressor to use
 * @param zone Allocation zone for the output buffer
 * @return List of buffers representing the compressed data (avoids combining when possible)
 */
suspend fun compressWithStreamingCompressor(
    buffer: ReadBuffer,
    compressor: SuspendingStreamingCompressor,
    zone: AllocationZone = AllocationZone.Heap,
): List<ReadBuffer> {
    val chunks = mutableListOf<ReadBuffer>()
    chunks.addAll(compressor.compress(buffer))
    chunks.addAll(compressor.flush())

    if (chunks.isEmpty()) return emptyList()

    // Strip sync flush marker (4 bytes: 00 00 FF FF) from end
    // Try to do this without combining buffers
    val lastChunk = chunks.last()
    if (lastChunk.remaining() >= 4) {
        // Check if last 4 bytes are the sync marker
        val pos = lastChunk.position()
        val endPos = pos + lastChunk.remaining()
        lastChunk.position(endPos - 4)
        val marker = lastChunk.readInt()
        if (marker == SYNC_FLUSH_MARKER) {
            // Just adjust the limit to exclude the marker
            lastChunk.position(pos)
            lastChunk.setLimit(endPos - 4)
            // Remove empty chunks
            if (lastChunk.remaining() == 0) {
                chunks.removeLast()
            }
            return chunks
        }
        lastChunk.position(pos)
    }

    // Marker spans multiple chunks or doesn't match - need to combine and strip
    val combined = combineChunks(chunks, zone)
    chunks.closeAll() // Free original native buffers after combining
    return listOf(stripSyncFlushMarkerInPlace(combined))
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
    zone: AllocationZone,
    pool: BufferPool? = null,
): ReadBuffer {
    if (chunks.isEmpty()) return ReadBuffer.EMPTY_BUFFER
    // Always copy to a new buffer. The single-chunk shortcut (returning chunks[0] directly)
    // causes use-after-free on Linux when callers do chunks.closeAll() after combining,
    // since combineChunks would have returned the same object that gets freed.
    val totalSize = chunks.sumOf { it.remaining() }
    if (totalSize == 0) return ReadBuffer.EMPTY_BUFFER
    val result = pool?.acquire(totalSize) ?: PlatformBuffer.allocate(totalSize, zone)
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
        val marker = buffer.readInt()
        buffer.position(pos)
        if (marker == SYNC_FLUSH_MARKER) {
            buffer.setLimit(buffer.limit() - 4)
        }
    }
    return buffer
}

/**
 * Decompresses a buffer using a streaming decompressor for WebSocket permessage-deflate.
 *
 * WebSocket permessage-deflate strips the Z_SYNC_FLUSH marker before transmission,
 * so this function appends it back before decompression using the provided decompressor.
 * Returns a list of buffers to minimize copies.
 *
 * @param buffer The compressed input buffer
 * @param decompressor The streaming decompressor to use
 * @param zone Allocation zone for intermediate buffers
 * @return List of buffers representing the decompressed data
 */
suspend fun decompressWithStreamingDecompressor(
    buffer: ReadBuffer,
    decompressor: SuspendingStreamingDecompressor,
): List<ReadBuffer> {
    val chunks = mutableListOf<ReadBuffer>()
    chunks.addAll(decompressor.decompress(buffer))
    // Append sync marker (4 bytes: 00 00 FF FF) and decompress it
    SYNC_FLUSH_MARKER_BUFFER.position(0)
    chunks.addAll(decompressor.decompress(SYNC_FLUSH_MARKER_BUFFER))
    chunks.addAll(decompressor.flush())
    return chunks
}

// ---- Sync (non-suspend) compression functions ----
// These use callback-based StreamingCompressor/StreamingDecompressor to avoid
// coroutine suspend/resume overhead (futex calls on Linux K/N).

/**
 * Compresses a buffer using a sync streaming compressor for WebSocket permessage-deflate.
 *
 * Same logic as [compressWithStreamingCompressor] but without suspend overhead.
 * Uses the callback-based [StreamingCompressor] API directly.
 */
internal fun compressSync(
    buffer: ReadBuffer,
    compressor: StreamingCompressor,
    zone: AllocationZone = AllocationZone.Heap,
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

    // Marker spans multiple chunks or doesn't match - need to combine and strip
    val combined = combineChunks(chunks, zone, pool)
    chunks.freeAll()
    return listOf(stripSyncFlushMarkerInPlace(combined))
}

/**
 * Decodes a decompressed chunk to StringBuilder and frees it. Shared by sync and suspend paths.
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
 *
 * Same logic as [decompressToBuffer] but without suspend overhead.
 */
internal fun decompressToBufferSync(
    buffer: ReadBuffer,
    decompressor: StreamingDecompressor,
    zone: AllocationZone = AllocationZone.Heap,
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

    // Sync marker flush — see decompressToStringSync for rationale
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
    val output = PlatformBuffer.allocate(totalSize, zone)
    for (chunk in chunks) {
        chunk.position(0)
        output.write(chunk)
    }
    chunks.freeAll()
    output.resetForRead()
    return output
}

/**
 * Decompresses a buffer directly to a String.
 *
 * Uses [StreamingStringDecoder] for platform-optimized UTF-8 decoding with automatic
 * multi-byte boundary handling. Each decompressed chunk is decoded and freed immediately,
 * keeping peak memory to one chunk + StringBuilder.
 *
 * @param buffer The compressed input buffer
 * @param decompressor The streaming decompressor to use
 * @param decoder The streaming string decoder for UTF-8 boundary handling
 * @return The decompressed string
 */
suspend fun decompressToString(
    buffer: ReadBuffer,
    decompressor: SuspendingStreamingDecompressor,
    decoder: StreamingStringDecoder,
): String {
    val sb = StringBuilder()
    decompressor.decompress(buffer).forEach { decodeAndFree(it, decoder, sb) }
    SYNC_FLUSH_MARKER_BUFFER.position(0)
    decompressor.decompress(SYNC_FLUSH_MARKER_BUFFER).forEach { decodeAndFree(it, decoder, sb) }
    decompressor.flush().forEach { decodeAndFree(it, decoder, sb) }
    decoder.finish(sb)
    decoder.reset()
    return sb.toString()
}

/**
 * Decompresses a buffer directly to a single output buffer, processing chunks as they come.
 *
 * @param buffer The compressed input buffer
 * @param decompressor The streaming decompressor to use
 * @param zone Allocation zone for the output buffer
 * @return The decompressed buffer
 */
suspend fun decompressToBuffer(
    buffer: ReadBuffer,
    decompressor: SuspendingStreamingDecompressor,
    zone: AllocationZone = AllocationZone.Heap,
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

    decompressor.decompress(buffer).forEach { chunk -> addChunk(chunk) }

    SYNC_FLUSH_MARKER_BUFFER.position(0)
    decompressor.decompress(SYNC_FLUSH_MARKER_BUFFER).forEach { chunk -> addChunk(chunk) }
    decompressor.flush().forEach { chunk -> addChunk(chunk) }

    // Single chunk optimization - no copy needed (caller takes ownership)
    if (chunks.size == 1) {
        return chunks[0]
    }

    if (totalSize == 0) {
        chunks.closeAll()
        return ReadBuffer.EMPTY_BUFFER
    }

    // Combine chunks into final output
    val output = PlatformBuffer.allocate(totalSize, zone)
    for (chunk in chunks) {
        chunk.position(0)
        output.write(chunk)
    }
    chunks.closeAll() // Free native buffers after copying
    output.resetForRead()
    return output
}

/**
 * Closes a buffer if it implements SuspendCloseable (e.g., NativeBuffer on Linux).
 * On Linux, NativeBuffer uses malloc/free and MUST be explicitly closed.
 * On JVM, this is a no-op (DirectByteBuffer is managed by GC Cleaner).
 */
internal suspend fun ReadBuffer.closeIfNeeded() {
    (this as? SuspendCloseable)?.close()
}

/**
 * Closes all buffers in a list that implement SuspendCloseable.
 */
internal suspend fun List<ReadBuffer>.closeAll() {
    for (buffer in this) {
        buffer.closeIfNeeded()
    }
}
