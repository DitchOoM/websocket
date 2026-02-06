package com.ditchoom.websocket.frame

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.SuspendingStreamingCompressor
import com.ditchoom.websocket.MaskingKey
import com.ditchoom.websocket.Opcode
import com.ditchoom.websocket.closeAll
import com.ditchoom.websocket.closeIfNeeded
import com.ditchoom.websocket.combineChunks
import com.ditchoom.websocket.compressWebsocketBuffer
import com.ditchoom.websocket.compressWithStreamingCompressor
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
    private val compressor: SuspendingStreamingCompressor? = null,
    private val compressionEnabled: Boolean = false,
    private val clientMode: Boolean = true,
    private val allocationZone: AllocationZone = AllocationZone.Direct,
) {
    /**
     * Writes a text frame with zero intermediate allocations.
     *
     * Single allocation: calculates UTF-8 size, allocates one buffer for
     * header + payload, writes string directly, applies mask in-place.
     */
    suspend fun writeTextFrame(
        text: String,
        fin: Boolean = true,
    ): ReadBuffer {
        // Fast path for empty text
        if (text.isEmpty()) {
            return writeFrame(Opcode.Text, EMPTY_BUFFER, fin)
        }

        // Calculate UTF-8 byte size (O(n) but no allocation)
        val payloadSize = Utf8.byteSize(text)

        // Check if compression would help
        if (compressionEnabled && compressor != null && payloadSize > 0) {
            // Need intermediate buffer for compression comparison
            val payload = PlatformBuffer.allocate(payloadSize, allocationZone)
            payload.writeString(text, Charset.UTF8)
            payload.resetForRead()
            val frame = writeFrame(Opcode.Text, payload, fin)
            payload.closeIfNeeded() // Free temp payload after frame serialization
            return frame
        }

        // Zero-copy path: single allocation for header + payload
        val header =
            if (clientMode) {
                PackedFrameHeader.forClient(fin, false, Opcode.Text, payloadSize)
            } else {
                PackedFrameHeader.forServer(fin, false, Opcode.Text, payloadSize)
            }
        val headerSize = header.headerSize(payloadSize, clientMode)
        val frameSize = headerSize + payloadSize

        val buffer = PlatformBuffer.allocate(frameSize, allocationZone) as ReadWriteBuffer

        // Write header
        buffer.writeShort(header.packed)
        writeExtendedLength(buffer, payloadSize)

        // Write mask and payload
        if (clientMode) {
            val mask = MaskingKey.FourByteMaskingKey()
            buffer.writeInt(mask.packed)
            val payloadStart = buffer.position()
            buffer.writeString(text, Charset.UTF8)
            val payloadEnd = buffer.position()

            // Apply SIMD-accelerated XOR mask in-place
            buffer.position(payloadStart)
            buffer.setLimit(payloadEnd)
            buffer.xorMask(mask.packed)
            buffer.position(payloadEnd)
            buffer.setLimit(buffer.capacity)
        } else {
            buffer.writeString(text, Charset.UTF8)
        }

        buffer.resetForRead()
        return buffer
    }

    /**
     * Writes a binary frame.
     */
    suspend fun writeBinaryFrame(
        data: ReadBuffer,
        fin: Boolean = true,
    ): ReadBuffer = writeFrame(Opcode.Binary, data, fin)

    /**
     * Writes a continuation frame.
     */
    suspend fun writeContinuationFrame(
        data: ReadBuffer,
        fin: Boolean,
    ): ReadBuffer = writeFrame(Opcode.Continuation, data, fin)

    /**
     * Writes a close frame.
     *
     * For small payloads (status code + short reason), uses single allocation.
     */
    suspend fun writeCloseFrame(
        statusCode: UShort? = null,
        reason: String? = null,
    ): ReadBuffer {
        if (statusCode == null) {
            return writeControlFrame(Opcode.Close, EMPTY_BUFFER)
        }

        // Calculate payload size: 2 bytes for code + reason (max 123 bytes)
        val reasonSize =
            if (reason != null) {
                Utf8.byteSize(reason).coerceAtMost(123)
            } else {
                0
            }
        val payloadSize = 2 + reasonSize

        // Single allocation for entire frame
        val header =
            if (clientMode) {
                PackedFrameHeader.forClient(true, false, Opcode.Close, payloadSize)
            } else {
                PackedFrameHeader.forServer(true, false, Opcode.Close, payloadSize)
            }
        val headerSize = header.headerSize(payloadSize, clientMode)
        val frameSize = headerSize + payloadSize

        val buffer = PlatformBuffer.allocate(frameSize, allocationZone) as ReadWriteBuffer

        // Write header
        buffer.writeShort(header.packed)
        // No extended length for control frames (max 125 bytes)

        // Write mask and payload
        if (clientMode) {
            val mask = MaskingKey.FourByteMaskingKey()
            buffer.writeInt(mask.packed)
            val payloadStart = buffer.position()

            // Write status code
            buffer.writeShort(statusCode.toShort())

            // Write reason directly (truncated to fit)
            if (reason != null && reasonSize > 0) {
                val truncated = Utf8.truncateToByteSize(reason, 123)
                buffer.writeString(truncated, Charset.UTF8)
            }

            val payloadEnd = buffer.position()

            // Apply SIMD-accelerated XOR mask in-place
            buffer.position(payloadStart)
            buffer.setLimit(payloadEnd)
            buffer.xorMask(mask.packed)
            buffer.position(payloadEnd)
            buffer.setLimit(buffer.capacity)
        } else {
            buffer.writeShort(statusCode.toShort())
            if (reason != null && reasonSize > 0) {
                val truncated = Utf8.truncateToByteSize(reason, 123)
                buffer.writeString(truncated, Charset.UTF8)
            }
        }

        buffer.resetForRead()
        return buffer
    }

    /**
     * Writes a ping frame.
     */
    suspend fun writePingFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
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
    suspend fun writePongFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
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

        val buffer = PlatformBuffer.allocate(frameSize, allocationZone) as ReadWriteBuffer

        buffer.writeShort(header.packed)

        if (clientMode) {
            val mask = MaskingKey.FourByteMaskingKey()
            buffer.writeInt(mask.packed)
            val payloadStart = buffer.position()
            payload.position(0)
            buffer.write(payload)
            val payloadEnd = buffer.position()

            buffer.position(payloadStart)
            buffer.setLimit(payloadEnd)
            buffer.xorMask(mask.packed)
            buffer.position(payloadEnd)
            buffer.setLimit(buffer.capacity)
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
    suspend fun writeFrame(
        opcode: Opcode,
        payload: ReadBuffer,
        fin: Boolean = true,
        compress: Boolean = compressionEnabled,
    ): ReadBuffer {
        val shouldCompress = compress && !opcode.isControlFrame() && payload.remaining() > 0
        var rsv1 = false
        var createdPayload = false // Track if we created a new buffer for finalPayload
        val finalPayload: ReadBuffer

        if (shouldCompress && compressor != null) {
            val originalSize = payload.remaining()
            val chunks = compressWithStreamingCompressor(payload, compressor)
            val compressedSize = totalRemaining(chunks)

            if (compressedSize < originalSize) {
                rsv1 = true
                compressor.reset()
                finalPayload = combineChunks(chunks, allocationZone)
                chunks.closeAll() // Free original compressed chunks after combining
                createdPayload = true
            } else {
                payload.resetForRead()
                compressor.reset()
                chunks.closeAll() // Free unused compressed chunks
                finalPayload = payload
            }
        } else if (shouldCompress) {
            val originalSize = payload.remaining()
            val compressed = payload.compressWebsocketBuffer()

            if (compressed.remaining() < originalSize) {
                rsv1 = true
                finalPayload = compressed
                createdPayload = true
            } else {
                compressed.closeIfNeeded() // Free unused compressed buffer
                payload.resetForRead()
                finalPayload = payload
            }
        } else {
            finalPayload = payload
        }

        val frame = serializeFrame(fin, rsv1, opcode, finalPayload)
        if (createdPayload) {
            finalPayload.closeIfNeeded() // Free temp compressed payload after serialization
        }
        return frame
    }

    /**
     * Serializes a frame with single allocation.
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

        val buffer = PlatformBuffer.allocate(frameSize, allocationZone) as ReadWriteBuffer

        // Write header
        buffer.writeShort(header.packed)
        writeExtendedLength(buffer, payloadSize)

        // Write mask and payload
        if (clientMode) {
            val mask = MaskingKey.FourByteMaskingKey()
            buffer.writeInt(mask.packed)
            val payloadStart = buffer.position()
            payload.position(0)
            buffer.write(payload)
            val payloadEnd = buffer.position()

            // Apply SIMD-accelerated XOR mask in-place
            buffer.position(payloadStart)
            buffer.setLimit(payloadEnd)
            buffer.xorMask(mask.packed)
            buffer.position(payloadEnd)
            buffer.setLimit(buffer.capacity)
        } else {
            payload.position(0)
            buffer.write(payload)
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

/**
 * UTF-8 utilities using only primitives - no allocations.
 */
internal object Utf8 {
    /**
     * Calculates the UTF-8 byte size of a string without allocation.
     * Uses a lookup approach based on Unicode code point ranges.
     */
    fun byteSize(text: String): Int {
        var size = 0
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i].code
            when {
                // ASCII (most common case first)
                c <= 0x7F -> {
                    size += 1
                    i++
                }
                // 2-byte UTF-8
                c <= 0x7FF -> {
                    size += 2
                    i++
                }
                // Check for surrogate pair (4-byte UTF-8)
                c in 0xD800..0xDBFF && i + 1 < len -> {
                    val next = text[i + 1].code
                    if (next in 0xDC00..0xDFFF) {
                        // Valid surrogate pair
                        size += 4
                        i += 2
                    } else {
                        // Invalid surrogate, encode as 3 bytes
                        size += 3
                        i++
                    }
                }
                // 3-byte UTF-8 (BMP character)
                else -> {
                    size += 3
                    i++
                }
            }
        }
        return size
    }

    /**
     * Truncates a string to fit within maxBytes UTF-8 bytes.
     * Returns the original string if it fits, avoiding allocation.
     */
    fun truncateToByteSize(
        text: String,
        maxBytes: Int,
    ): String {
        var size = 0
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i].code
            val charBytes: Int
            val advance: Int

            when {
                c <= 0x7F -> {
                    charBytes = 1
                    advance = 1
                }
                c <= 0x7FF -> {
                    charBytes = 2
                    advance = 1
                }
                c in 0xD800..0xDBFF && i + 1 < len && text[i + 1].code in 0xDC00..0xDFFF -> {
                    charBytes = 4
                    advance = 2
                }
                else -> {
                    charBytes = 3
                    advance = 1
                }
            }

            if (size + charBytes > maxBytes) {
                // Return truncated - this allocates, but only when truncation is needed
                return text.substring(0, i)
            }

            size += charBytes
            i += advance
        }

        // Fits entirely - return original reference (no allocation)
        return text
    }
}
