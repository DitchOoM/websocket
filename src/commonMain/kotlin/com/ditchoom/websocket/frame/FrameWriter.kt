package com.ditchoom.websocket.frame

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.websocket.MaskingKey
import com.ditchoom.websocket.Opcode
import com.ditchoom.websocket.compressSync
import com.ditchoom.websocket.totalRemaining
import kotlin.jvm.JvmInline

/**
 * Packed WebSocket frame header (first 2 bytes) stored in a Short.
 * Zero allocation - all field access via inline bit operations.
 *
 * Layout: [FIN:1][RSV1:1][RSV2:1][RSV3:1][OPCODE:4][MASK:1][PAYLOAD_LEN:7]
 */
@JvmInline
internal value class PackedFrameHeader(
    val packed: Short,
) {
    companion object {
        /** Creates a packed header for client frames (always masked). */
        fun forClient(
            fin: Boolean,
            rsv1: Boolean,
            opcode: Opcode,
            payloadSize: Int,
        ): PackedFrameHeader {
            val byte1 =
                (opcode.value.toInt() and 0x0F) or
                    (if (fin) 0x80 else 0) or
                    (if (rsv1) 0x40 else 0)

            val payloadLen7 =
                when {
                    payloadSize <= 125 -> payloadSize
                    payloadSize <= 65535 -> 126
                    else -> 127
                }
            // Client frames are always masked
            val byte2 = payloadLen7 or 0x80

            return PackedFrameHeader(((byte1 shl 8) or byte2).toShort())
        }

        /** Creates a packed header for server frames (never masked). */
        fun forServer(
            fin: Boolean,
            rsv1: Boolean,
            opcode: Opcode,
            payloadSize: Int,
        ): PackedFrameHeader {
            val byte1 =
                (opcode.value.toInt() and 0x0F) or
                    (if (fin) 0x80 else 0) or
                    (if (rsv1) 0x40 else 0)

            val payloadLen7 =
                when {
                    payloadSize <= 125 -> payloadSize
                    payloadSize <= 65535 -> 126
                    else -> 127
                }

            return PackedFrameHeader(((byte1 shl 8) or payloadLen7).toShort())
        }
    }

    /** Calculates total header size including extended length and mask. */
    fun headerSize(
        payloadSize: Int,
        masked: Boolean,
    ): Int {
        val extendedLen =
            when {
                payloadSize <= 125 -> 0
                payloadSize <= 65535 -> 2
                else -> 8
            }
        val maskSize = if (masked) 4 else 0
        return 2 + extendedLen + maskSize
    }
}

/**
 * WebSocket frame writer with optional compression support.
 *
 * Serializes WebSocket frames per RFC 6455 Section 5.2. Supports:
 * - Client masking (required for client->server frames)
 * - permessage-deflate compression (RFC 7692)
 * - Zero-copy buffer operations with SIMD-accelerated masking
 *
 * ## Zero-Copy Design
 *
 * - Single buffer allocation for entire frame (header + payload)
 * - Direct string encoding via writeString() - no intermediate ByteArray
 * - In-place SIMD XOR masking via xorMask()
 * - Value classes for header packing - no object allocation
 */
class FrameWriter(
    private val compressor: StreamingCompressor? = null,
    private val compressionEnabled: Boolean = false,
    private val clientMode: Boolean = true,
    private val allocationZone: AllocationZone = AllocationZone.Direct,
    private val pool: BufferPool? = null,
    private val resetCompressorPerMessage: Boolean = true,
) {
    private fun allocateBuffer(size: Int): ReadWriteBuffer =
        pool?.acquire(size) ?: (PlatformBuffer.allocate(size, allocationZone) as ReadWriteBuffer)

    private fun releaseBuffer(buffer: ReadBuffer) {
        if (pool != null && buffer is ReadWriteBuffer) {
            pool.release(buffer)
        } else {
            buffer.freeIfNeeded()
        }
    }

    /**
     * Writes a text frame.
     *
     * Encodes the string to a temp pool buffer (single O(n) pass), then
     * serializes the frame with fused copy+mask via xorMaskCopy (second O(n) pass).
     * Avoids the previous 3-pass approach (byteSize + writeString + xorMask).
     */
    fun writeTextFrame(
        text: String,
        fin: Boolean = true,
    ): ReadBuffer {
        // Fast path for empty text
        if (text.isEmpty()) {
            return writeFrame(Opcode.Text, EMPTY_BUFFER, fin)
        }

        // text.length * 3 is a safe upper bound (worst case: all 3-byte BMP chars).
        val payload = allocateBuffer(text.length * 3)
        payload.writeString(text, Charset.UTF8)
        payload.resetForRead()
        val frame = writeFrame(Opcode.Text, payload, fin)
        releaseBuffer(payload)
        return frame
    }

    /**
     * Writes a binary frame.
     */
    fun writeBinaryFrame(
        data: ReadBuffer,
        fin: Boolean = true,
    ): ReadBuffer = writeFrame(Opcode.Binary, data, fin)

    /**
     * Writes a continuation frame.
     */
    fun writeContinuationFrame(
        data: ReadBuffer,
        fin: Boolean,
    ): ReadBuffer = writeFrame(Opcode.Continuation, data, fin)

    /**
     * Writes a close frame.
     *
     * Close frame payload: 2-byte status code + UTF-8 reason (max 123 bytes per RFC 6455).
     */
    fun writeCloseFrame(
        statusCode: UShort? = null,
        reason: String? = null,
    ): ReadBuffer {
        if (statusCode == null) {
            return writeControlFrame(Opcode.Close, EMPTY_BUFFER)
        }

        // Pre-truncate to 123 chars; for ASCII (typical close reasons) chars == bytes
        val truncated = if (reason != null && reason.length > 123) reason.substring(0, 123) else reason
        val payload = allocateBuffer(2 + (truncated?.length ?: 0) * 3)
        payload.writeShort(statusCode.toShort())
        if (truncated != null && truncated.isNotEmpty()) {
            payload.writeString(truncated, Charset.UTF8)
        }
        // Cap at 125 bytes total (2 status + max 123 reason) per RFC 6455
        if (payload.position() > 125) payload.position(125)
        payload.resetForRead()
        val frame = writeControlFrame(Opcode.Close, payload)
        releaseBuffer(payload)
        return frame
    }

    /**
     * Writes a ping frame.
     */
    fun writePingFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
        val truncated =
            if (data.remaining() > 125) {
                data.readBytes(125)
            } else {
                data
            }
        return writeControlFrame(Opcode.Ping, truncated)
    }

    /**
     * Writes a pong frame.
     */
    fun writePongFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
        val truncated =
            if (data.remaining() > 125) {
                data.readBytes(125)
            } else {
                data
            }
        return writeControlFrame(Opcode.Pong, truncated)
    }

    /**
     * Writes a control frame (close, ping, pong).
     * Control frames are never compressed and always have fin=true.
     */
    private fun writeControlFrame(
        opcode: Opcode,
        payload: ReadBuffer,
    ): ReadBuffer {
        val payloadSize = payload.remaining()

        val header =
            if (clientMode) {
                PackedFrameHeader.forClient(true, false, opcode, payloadSize)
            } else {
                PackedFrameHeader.forServer(true, false, opcode, payloadSize)
            }
        val headerSize = header.headerSize(payloadSize, clientMode)
        val frameSize = headerSize + payloadSize

        val buffer = allocateBuffer(frameSize)

        buffer.writeShort(header.packed)

        if (clientMode) {
            val mask = MaskingKey.FourByteMaskingKey()
            buffer.writeInt(mask.packed)
            payload.position(0)
            buffer.xorMaskCopy(payload, mask.packed)
        } else {
            payload.position(0)
            buffer.write(payload)
        }

        buffer.resetForRead()
        return buffer
    }

    /**
     * Writes a data frame with optional compression.
     */
    fun writeFrame(
        opcode: Opcode,
        payload: ReadBuffer,
        fin: Boolean = true,
        compress: Boolean = compressionEnabled,
    ): ReadBuffer {
        val shouldCompress = compress && !opcode.isControlFrame() && payload.remaining() > 0

        if (shouldCompress && compressor != null) {
            val originalSize = payload.remaining()
            val chunks = compressSync(payload, compressor, pool = pool)
            val compressedSize = totalRemaining(chunks)

            // With context takeover (!resetCompressorPerMessage), always send compressed
            // even if larger. Falling back to uncompressed would desync the LZ77 windows:
            // the compressor's window includes this message, but the server's decompressor
            // never processes the uncompressed frame, so future back-references break.
            if (compressedSize < originalSize || !resetCompressorPerMessage) {
                if (resetCompressorPerMessage) compressor.reset()
                // Serialize directly from chunks — no intermediate combineChunks buffer
                val frame = serializeFrameFromChunks(fin, rsv1 = true, opcode, chunks, compressedSize)
                chunks.freeAll()
                return frame
            } else {
                payload.resetForRead()
                compressor.reset()
                chunks.freeAll()
                return serializeFrame(fin, rsv1 = false, opcode, payload)
            }
        }

        return serializeFrame(fin, rsv1 = false, opcode, payload)
    }

    /**
     * Serializes a frame from a single payload buffer with fused copy + mask.
     */
    private fun serializeFrame(
        fin: Boolean,
        rsv1: Boolean,
        opcode: Opcode,
        payload: ReadBuffer,
    ): ReadBuffer {
        val payloadSize = payload.remaining()

        val header =
            if (clientMode) {
                PackedFrameHeader.forClient(fin, rsv1, opcode, payloadSize)
            } else {
                PackedFrameHeader.forServer(fin, rsv1, opcode, payloadSize)
            }
        val headerSize = header.headerSize(payloadSize, clientMode)
        val frameSize = headerSize + payloadSize

        val buffer = allocateBuffer(frameSize)

        // Write header
        buffer.writeShort(header.packed)
        writeExtendedLength(buffer, payloadSize)

        // Write payload with fused copy + mask (single pass)
        if (clientMode) {
            val mask = MaskingKey.FourByteMaskingKey()
            buffer.writeInt(mask.packed)
            payload.position(0)
            buffer.xorMaskCopy(payload, mask.packed)
        } else {
            payload.position(0)
            buffer.write(payload)
        }

        buffer.resetForRead()
        return buffer
    }

    /**
     * Serializes a frame directly from compressed chunks with fused copy + mask.
     * Eliminates the intermediate combineChunks() buffer — chunks go directly
     * into the frame buffer with masking applied in a single pass per chunk.
     */
    private fun serializeFrameFromChunks(
        fin: Boolean,
        rsv1: Boolean,
        opcode: Opcode,
        chunks: List<ReadBuffer>,
        payloadSize: Int,
    ): ReadBuffer {
        val header =
            if (clientMode) {
                PackedFrameHeader.forClient(fin, rsv1, opcode, payloadSize)
            } else {
                PackedFrameHeader.forServer(fin, rsv1, opcode, payloadSize)
            }
        val headerSize = header.headerSize(payloadSize, clientMode)
        val frameSize = headerSize + payloadSize

        val buffer = allocateBuffer(frameSize)

        // Write header
        buffer.writeShort(header.packed)
        writeExtendedLength(buffer, payloadSize)

        // Write each chunk with fused copy + mask, tracking mask offset
        if (clientMode) {
            val mask = MaskingKey.FourByteMaskingKey()
            buffer.writeInt(mask.packed)
            var maskOffset = 0
            for (chunk in chunks) {
                val chunkSize = chunk.remaining()
                buffer.xorMaskCopy(chunk, mask.packed, maskOffset)
                maskOffset += chunkSize
            }
        } else {
            for (chunk in chunks) {
                buffer.write(chunk)
            }
        }

        buffer.resetForRead()
        return buffer
    }

    /** Writes extended payload length if needed. */
    private fun writeExtendedLength(
        buffer: ReadWriteBuffer,
        payloadSize: Int,
    ) {
        when {
            payloadSize > 65535 -> buffer.writeLong(payloadSize.toLong())
            payloadSize > 125 -> buffer.writeShort(payloadSize.toShort())
        }
    }
}
