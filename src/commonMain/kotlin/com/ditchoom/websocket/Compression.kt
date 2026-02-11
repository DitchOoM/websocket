package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
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
 * Decompresses a buffer directly to a String using a sync streaming decompressor.
 *
 * Same hybrid strategy as [decompressToString] but without suspend overhead.
 */
internal fun decompressToStringSync(
    buffer: ReadBuffer,
    decompressor: StreamingDecompressor,
    pool: BufferPool? = null,
): String {
    var totalSize = 0
    val chunks = mutableListOf<ReadBuffer>()

    fun addChunk(chunk: ReadBuffer) {
        if (chunk.position() != 0) {
            chunk.position(0)
        }
        val remaining = chunk.remaining()
        if (remaining > 0) {
            totalSize += remaining
            chunks.add(chunk)
        }
    }

    // Collect all decompressed chunks
    decompressor.decompress(buffer) { chunk -> addChunk(chunk) }

    // Append sync marker and decompress - this flushes all pending output.
    // Per RFC 7692, the sync marker (00 00 FF FF) is stripped from the wire format
    // and must be re-appended before decompression.
    SYNC_FLUSH_MARKER_BUFFER.position(0)
    decompressor.decompress(SYNC_FLUSH_MARKER_BUFFER) { chunk -> addChunk(chunk) }
    // Emit any partial output buffered by the decompressor. Unlike finish(),
    // flush() does not set streamEnded, so context takeover continues working.
    decompressor.flush { chunk -> addChunk(chunk) }

    // Fast path: empty result
    if (totalSize == 0) {
        chunks.freeAll()
        return ""
    }

    // Fast path: single chunk - zero copy
    if (chunks.size == 1) {
        val chunk = chunks[0]
        val result = chunk.readString(chunk.remaining(), Charset.UTF8)
        chunk.freeIfNeeded()
        return result
    }

    // For small messages: combine into single buffer first, then decode once
    if (totalSize <= COMBINE_THRESHOLD_BYTES) {
        val combined = pool?.acquire(totalSize) ?: PlatformBuffer.allocate(totalSize, AllocationZone.Heap)
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

    // For large messages: use streaming to limit peak memory
    val sb = StringBuilder(totalSize)
    var pendingBoundary: Utf8Boundary = Utf8Boundary.EMPTY

    for ((index, chunk) in chunks.withIndex()) {
        val isLast = index == chunks.lastIndex
        chunk.position(0)
        pendingBoundary = appendChunkToStringBuilder(chunk, sb, pendingBoundary, isLast)
    }
    chunks.freeAll()

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

// Threshold for using combine-first strategy vs streaming.
// Below this, combining is faster. Above this, streaming saves memory.
private const val COMBINE_THRESHOLD_BYTES = 64 * 1024 // 64KB

/**
 * Decompresses a buffer directly to a String.
 *
 * Uses a hybrid strategy optimized for both speed and memory:
 * - Single chunk: Zero-copy path, reads directly from decompressor output
 * - Small messages (≤64KB): Combines chunks first for faster decoding on K/N
 * - Large messages (>64KB): Streams with UTF-8 boundary handling to limit memory
 *
 * @param buffer The compressed input buffer
 * @param decompressor The streaming decompressor to use
 * @return The decompressed string
 */
suspend fun decompressToString(
    buffer: ReadBuffer,
    decompressor: SuspendingStreamingDecompressor,
): String {
    var totalSize = 0
    val chunks = mutableListOf<ReadBuffer>()

    fun addChunk(chunk: ReadBuffer) {
        if (chunk.position() != 0) {
            chunk.position(0)
        }
        val remaining = chunk.remaining()
        if (remaining > 0) {
            totalSize += remaining
            chunks.add(chunk)
        }
    }

    // Collect all decompressed chunks
    decompressor.decompress(buffer).forEach { chunk -> addChunk(chunk) }

    // Append sync marker and decompress - this flushes all pending output
    SYNC_FLUSH_MARKER_BUFFER.position(0)
    decompressor.decompress(SYNC_FLUSH_MARKER_BUFFER).forEach { chunk -> addChunk(chunk) }
    decompressor.flush().forEach { chunk -> addChunk(chunk) }

    // Fast path: empty result
    if (totalSize == 0) {
        chunks.closeAll()
        return ""
    }

    // Fast path: single chunk - zero copy, read directly from decompressor output
    if (chunks.size == 1) {
        val chunk = chunks[0]
        val result = chunk.readString(chunk.remaining(), Charset.UTF8)
        chunk.closeIfNeeded() // Free native buffer after reading
        return result
    }

    // For small messages: combine into single buffer first (native memcpy), then decode once
    // This is faster on K/N than multiple readString() calls + StringBuilder
    if (totalSize <= COMBINE_THRESHOLD_BYTES) {
        val combined = PlatformBuffer.allocate(totalSize, AllocationZone.Heap)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        chunks.closeAll() // Free native buffers after copying
        combined.resetForRead()
        return combined.readString(totalSize, Charset.UTF8)
    }

    // For large messages: use streaming to limit peak memory
    // Pre-size StringBuilder to avoid resize allocations
    val sb = StringBuilder(totalSize)
    var pendingBoundary: Utf8Boundary = Utf8Boundary.EMPTY

    for ((index, chunk) in chunks.withIndex()) {
        val isLast = index == chunks.lastIndex
        chunk.position(0)
        pendingBoundary = appendChunkToStringBuilder(chunk, sb, pendingBoundary, isLast)
    }
    chunks.closeAll() // Free native buffers after reading

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
 * Appends a chunk to StringBuilder, handling UTF-8 boundaries.
 * Returns the pending boundary bytes for the next chunk.
 */
private fun appendChunkToStringBuilder(
    chunk: ReadBuffer,
    sb: StringBuilder,
    pendingBoundary: Utf8Boundary,
    isLast: Boolean,
): Utf8Boundary {
    // Ensure chunk is ready to read from the start
    // (defensive: decompressor may return chunks with position != 0)
    if (chunk.position() != 0) {
        chunk.position(0)
    }
    val remaining = chunk.remaining()
    if (remaining == 0) return pendingBoundary

    var currentBoundary = pendingBoundary

    // Handle pending bytes from previous chunk
    if (currentBoundary.isNotEmpty()) {
        currentBoundary = completeBoundary(currentBoundary, chunk, sb)
    }

    val chunkRemaining = chunk.remaining()
    if (chunkRemaining == 0) return currentBoundary

    if (isLast) {
        // Last chunk - decode everything remaining
        sb.append(chunk.readString(chunkRemaining))
        return Utf8Boundary.EMPTY
    } else {
        // Find safe cut point (only checks last 1-4 bytes)
        val tailBytes = findIncompleteUtf8Tail(chunk)

        if (tailBytes == 0) {
            // No split - decode entire chunk
            sb.append(chunk.readString(chunkRemaining))
            return Utf8Boundary.EMPTY
        } else {
            // Decode up to the safe point
            val safeLength = chunkRemaining - tailBytes
            if (safeLength > 0) {
                sb.append(chunk.readString(safeLength))
            }
            // Pack remaining bytes for next iteration
            return packTailBytes(chunk, tailBytes)
        }
    }
}

/**
 * Converts a list of buffer chunks to a String with zero intermediate buffer allocation.
 *
 * This function handles UTF-8 characters that may be split across chunk boundaries
 * by using a Long as an in-register buffer for boundary bytes (max 4 bytes for UTF-8).
 *
 * Memory optimization: Instead of combining all chunks into one buffer before decoding,
 * this decodes each chunk directly and only handles the 1-4 boundary bytes specially.
 *
 * @param chunks The list of buffers containing UTF-8 encoded data
 * @return The decoded string
 */
internal fun chunksToUtf8String(chunks: List<ReadBuffer>): String {
    if (chunks.isEmpty()) return ""
    if (chunks.size == 1) {
        val chunk = chunks[0]
        return chunk.readString(chunk.remaining())
    }

    // Pre-size StringBuilder to avoid resize allocations during append
    // UTF-8 bytes ≤ UTF-16 code units, so this is an upper bound
    val totalBytes = totalRemaining(chunks)
    val sb = StringBuilder(totalBytes)
    var pendingBoundary: Utf8Boundary = Utf8Boundary.EMPTY

    for ((index, chunk) in chunks.withIndex()) {
        val isLast = index == chunks.lastIndex

        // Ensure chunk is ready to read from the start
        if (chunk.position() != 0) {
            chunk.position(0)
        }
        val remaining = chunk.remaining()

        if (remaining == 0) continue

        // Handle pending bytes from previous chunk
        if (pendingBoundary.isNotEmpty()) {
            pendingBoundary = completeBoundary(pendingBoundary, chunk, sb)
        }

        val chunkRemaining = chunk.remaining()
        if (chunkRemaining == 0) continue

        if (isLast) {
            // Last chunk - decode everything remaining
            sb.append(chunk.readString(chunkRemaining))
        } else {
            // Find safe cut point (only checks last 1-4 bytes)
            val tailBytes = findIncompleteUtf8Tail(chunk)

            if (tailBytes == 0) {
                // No split - decode entire chunk
                sb.append(chunk.readString(chunkRemaining))
            } else {
                // Decode up to the safe point
                val safeLength = chunkRemaining - tailBytes
                if (safeLength > 0) {
                    sb.append(chunk.readString(safeLength))
                }
                // Pack remaining bytes for next iteration
                pendingBoundary = packTailBytes(chunk, tailBytes)
            }
        }
    }

    return sb.toString()
}

/**
 * Value class that holds up to 4 UTF-8 boundary bytes in a Long register.
 * Zero allocation - the Long is used directly as storage.
 *
 * Layout: [b0:8][b1:8][b2:8][b3:8][unused:24][count:8]
 */
@kotlin.jvm.JvmInline
internal value class Utf8Boundary private constructor(
    private val packed: Long,
) {
    val byteCount: Int get() = (packed and 0xFF).toInt()
    val expectedLength: Int get() = ((packed ushr 8) and 0xFF).toInt()

    fun isNotEmpty(): Boolean = byteCount > 0

    private fun getByte(index: Int): Int = ((packed ushr (56 - index * 8)) and 0xFF).toInt()

    /**
     * Decodes the boundary bytes to a code point and appends to StringBuilder.
     */
    fun appendTo(sb: StringBuilder) {
        val codePoint =
            when (expectedLength) {
                2 -> ((getByte(0) and 0x1F) shl 6) or (getByte(1) and 0x3F)
                3 -> ((getByte(0) and 0x0F) shl 12) or ((getByte(1) and 0x3F) shl 6) or (getByte(2) and 0x3F)
                4 ->
                    ((getByte(0) and 0x07) shl 18) or ((getByte(1) and 0x3F) shl 12) or
                        ((getByte(2) and 0x3F) shl 6) or (getByte(3) and 0x3F)
                else -> getByte(0) // ASCII fallback
            }

        if (codePoint <= 0xFFFF) {
            sb.append(codePoint.toChar())
        } else {
            // Surrogate pair for code points > 0xFFFF (emoji, rare CJK, etc.)
            val adjusted = codePoint - 0x10000
            sb.append((0xD800 + (adjusted shr 10)).toChar())
            sb.append((0xDC00 + (adjusted and 0x3FF)).toChar())
        }
    }

    /**
     * Adds a byte to this boundary, returning a new Utf8Boundary.
     */
    fun addByte(b: Byte): Utf8Boundary {
        val currentCount = byteCount
        val newPacked = packed or ((b.toLong() and 0xFF) shl (56 - currentCount * 8))
        return Utf8Boundary((newPacked and BYTE_MASK) or ((currentCount + 1).toLong()))
    }

    companion object {
        val EMPTY = Utf8Boundary(0L)
        private const val BYTE_MASK = -256L // 0xFFFFFFFF_FFFFFF00 - masks out the lower 8 bits (count)

        /**
         * Creates a boundary with the first byte (lead byte) which determines expected length.
         */
        fun fromLeadByte(b: Byte): Utf8Boundary {
            val byteVal = b.toInt() and 0xFF
            val expectedLen =
                when {
                    byteVal < 0x80 -> 1
                    byteVal < 0xE0 -> 2
                    byteVal < 0xF0 -> 3
                    else -> 4
                }
            val packed = ((b.toLong() and 0xFF) shl 56) or (expectedLen.toLong() shl 8) or 1L
            return Utf8Boundary(packed)
        }
    }
}

/**
 * Finds how many bytes at the end of the chunk are part of an incomplete UTF-8 sequence.
 * Only examines the last 1-4 bytes using indexed access (O(1)).
 *
 * @return Number of trailing bytes that form an incomplete sequence (0-3)
 */
private fun findIncompleteUtf8Tail(chunk: ReadBuffer): Int {
    val remaining = chunk.remaining()
    if (remaining == 0) return 0

    val startPos = chunk.position()
    val endPos = startPos + remaining

    // Scan backwards from end - max 4 bytes
    var i = endPos - 1
    var continuationCount = 0

    // Count continuation bytes (10xxxxxx) at end
    while (i >= startPos && continuationCount < 4) {
        val b = chunk[i].toInt() and 0xFF
        if ((b and 0xC0) != 0x80) break // Found lead byte
        continuationCount++
        i--
    }

    if (i < startPos) {
        // All continuation bytes - malformed, return all
        return remaining.coerceAtMost(4)
    }

    // Check if sequence starting at i is complete
    val leadByte = chunk[i].toInt() and 0xFF
    val expectedLen =
        when {
            leadByte < 0x80 -> 1
            leadByte < 0xE0 -> 2
            leadByte < 0xF0 -> 3
            else -> 4
        }

    val actualLen = endPos - i
    return if (actualLen >= expectedLen) 0 else actualLen
}

/**
 * Packs the trailing bytes of a chunk into a Utf8Boundary.
 */
private fun packTailBytes(
    chunk: ReadBuffer,
    count: Int,
): Utf8Boundary {
    if (count == 0) return Utf8Boundary.EMPTY

    val pos = chunk.position()
    val firstByte = chunk[pos]
    var boundary = Utf8Boundary.fromLeadByte(firstByte)
    chunk.position(pos + 1)

    for (j in 1 until count) {
        boundary = boundary.addByte(chunk.readByte())
    }

    return boundary
}

/**
 * Completes a boundary by reading continuation bytes from the next chunk.
 * Appends the decoded character to sb and returns EMPTY, or returns
 * the still-incomplete boundary if chunk doesn't have enough bytes.
 */
private fun completeBoundary(
    boundary: Utf8Boundary,
    chunk: ReadBuffer,
    sb: StringBuilder,
): Utf8Boundary {
    var current = boundary
    val needed = current.expectedLength - current.byteCount

    repeat(needed.coerceAtMost(chunk.remaining())) {
        current = current.addByte(chunk.readByte())
    }

    return if (current.byteCount >= current.expectedLength) {
        current.appendTo(sb)
        Utf8Boundary.EMPTY
    } else {
        current // Still incomplete (chunk was too small)
    }
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
