package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.compressAsync
import com.ditchoom.buffer.compression.decompressAsync

// WebSocket permessage-deflate terminator: 0x00 0x00 0xFF 0xFF
private const val COMPRESSION_TERMINATOR = 0x0000FFFF

suspend fun ReadBuffer.decompressWebsocketBuffer(zone: AllocationZone = AllocationZone.Direct): ReadBuffer {
    // WebSocket permessage-deflate requires appending the terminator before decompression
    val wrappedBuffer = PlatformBuffer.allocate(this.remaining() + 4, zone)
    wrappedBuffer.write(this)
    wrappedBuffer.writeInt(COMPRESSION_TERMINATOR)
    wrappedBuffer.resetForRead()
    return decompressAsync(wrappedBuffer, CompressionAlgorithm.Raw, zone)
}

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
        if (last4Bytes == COMPRESSION_TERMINATOR) {
            compressed.setLimit(compressed.limit() - 4)
        }
    }
    return compressed
}
