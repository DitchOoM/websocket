package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.compressAsync
import com.ditchoom.buffer.compression.decompressAsync

private val COMPRESSION_TERMINATOR = byteArrayOf(0, 0, -1, -1)

suspend fun ReadBuffer.decompressWebsocketBuffer(
    zone: AllocationZone = AllocationZone.Direct
): ReadBuffer {
    // WebSocket permessage-deflate requires appending the terminator before decompression
    val wrappedBuffer = PlatformBuffer.allocate(this.remaining() + COMPRESSION_TERMINATOR.size, zone)
    wrappedBuffer.write(this)
    wrappedBuffer.writeBytes(COMPRESSION_TERMINATOR)
    wrappedBuffer.resetForRead()
    return decompressAsync(wrappedBuffer, CompressionAlgorithm.Raw, zone)
}

suspend fun ReadBuffer.compressWebsocketBuffer(
    level: Int = -1,
    zone: AllocationZone = AllocationZone.Heap
): ReadBuffer {
    val compressionLevel = when {
        level < 0 -> CompressionLevel.Default
        level == 0 -> CompressionLevel.NoCompression
        level == 1 -> CompressionLevel.BestSpeed
        level == 9 -> CompressionLevel.BestCompression
        else -> CompressionLevel.Custom(level.coerceIn(0, 9))
    }
    val compressed = compressAsync(this, CompressionAlgorithm.Raw, compressionLevel, zone)
    // WebSocket permessage-deflate requires stripping the terminator if present
    val compressionTerminatorSize = COMPRESSION_TERMINATOR.size
    if (compressed.remaining() >= compressionTerminatorSize) {
        val positionLastFourBytes = compressed.limit() - compressionTerminatorSize
        compressed.position(positionLastFourBytes)
        val last4Bytes = compressed.readByteArray(compressionTerminatorSize)
        compressed.position(0)
        if (COMPRESSION_TERMINATOR.contentEquals(last4Bytes)) {
            compressed.setLimit(compressed.limit() - compressionTerminatorSize)
        }
    }
    return compressed
}
