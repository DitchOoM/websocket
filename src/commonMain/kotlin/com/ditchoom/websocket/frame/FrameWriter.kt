package com.ditchoom.websocket.frame

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.compression.SuspendingStreamingCompressor
import com.ditchoom.websocket.MaskingKey
import com.ditchoom.websocket.Opcode
import com.ditchoom.websocket.combineChunks
import com.ditchoom.websocket.compressWebsocketBuffer
import com.ditchoom.websocket.compressWithStreamingCompressor
import com.ditchoom.websocket.totalRemaining

/**
 * WebSocket frame writer with optional compression support.
 *
 * Serializes WebSocket frames per RFC 6455 Section 5.2. Supports:
 * - Client masking (required for client->server frames)
 * - permessage-deflate compression (RFC 7692)
 * - Zero-copy buffer operations where possible
 *
 * ## RFC 6455 Section 5.2 - Base Framing Protocol
 * https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
 *
 * ## Usage
 *
 * ```kotlin
 * val writer = FrameWriter(compressor = myCompressor, compressionEnabled = true)
 *
 * // Write a text frame
 * val frameBuffer = writer.writeTextFrame("Hello, World!")
 *
 * // Write a binary frame
 * val binaryBuffer = writer.writeBinaryFrame(myData)
 *
 * // Write control frames
 * val pingBuffer = writer.writePingFrame(pingData)
 * val closeBuffer = writer.writeCloseFrame(1000u, "Normal closure")
 * ```
 */
class FrameWriter(
    private val compressor: SuspendingStreamingCompressor? = null,
    private val compressionEnabled: Boolean = false,
    private val clientMode: Boolean = true, // Client frames must be masked
) {
    /**
     * Writes a text frame.
     *
     * @param text The text content to send
     * @param fin Whether this is the final fragment (default true)
     * @return Buffer containing the serialized frame, ready to send
     */
    suspend fun writeTextFrame(
        text: String,
        fin: Boolean = true,
    ): ReadBuffer {
        val payload = text.encodeToByteArray().let { bytes ->
            val buffer = PlatformBuffer.allocate(bytes.size, AllocationZone.Heap)
            buffer.writeBytes(bytes)
            buffer.resetForRead()
            buffer
        }
        return writeFrame(Opcode.Text, payload, fin)
    }

    /**
     * Writes a binary frame.
     *
     * @param data The binary data to send
     * @param fin Whether this is the final fragment (default true)
     * @return Buffer containing the serialized frame, ready to send
     */
    suspend fun writeBinaryFrame(
        data: ReadBuffer,
        fin: Boolean = true,
    ): ReadBuffer = writeFrame(Opcode.Binary, data, fin)

    /**
     * Writes a continuation frame.
     *
     * Per RFC 6455 Section 5.4, continuation frames are used for fragmented messages.
     *
     * @param data The payload data
     * @param fin Whether this is the final fragment
     * @return Buffer containing the serialized frame
     */
    suspend fun writeContinuationFrame(
        data: ReadBuffer,
        fin: Boolean,
    ): ReadBuffer = writeFrame(Opcode.Continuation, data, fin)

    /**
     * Writes a close frame.
     *
     * Per RFC 6455 Section 5.5.1, close frames may include a status code and reason.
     *
     * @param statusCode Optional status code (1000 = normal closure)
     * @param reason Optional close reason (max 123 bytes as UTF-8)
     * @return Buffer containing the serialized frame
     */
    suspend fun writeCloseFrame(
        statusCode: UShort? = null,
        reason: String? = null,
    ): ReadBuffer {
        val payload = if (statusCode != null) {
            val reasonBytes = reason?.encodeToByteArray() ?: byteArrayOf()
            // RFC 6455 Section 5.5: Control frames max 125 bytes payload
            // Status code is 2 bytes, leaving 123 for reason
            val truncatedReason = if (reasonBytes.size > 123) {
                reasonBytes.copyOf(123)
            } else {
                reasonBytes
            }
            val buffer = PlatformBuffer.allocate(2 + truncatedReason.size, AllocationZone.Heap)
            buffer.writeShort(statusCode.toShort())
            if (truncatedReason.isNotEmpty()) {
                buffer.writeBytes(truncatedReason)
            }
            buffer.resetForRead()
            buffer
        } else {
            EMPTY_BUFFER
        }
        // Control frames are never compressed and always have fin=true
        return writeFrame(Opcode.Close, payload, fin = true, compress = false)
    }

    /**
     * Writes a ping frame.
     *
     * Per RFC 6455 Section 5.5.2, ping frames may include application data (max 125 bytes).
     *
     * @param data Optional ping payload
     * @return Buffer containing the serialized frame
     */
    suspend fun writePingFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
        // RFC 6455 Section 5.5: Control frames max 125 bytes payload
        val truncatedData = if (data.remaining() > 125) {
            data.readBytes(125)
        } else {
            data
        }
        return writeFrame(Opcode.Ping, truncatedData, fin = true, compress = false)
    }

    /**
     * Writes a pong frame.
     *
     * Per RFC 6455 Section 5.5.3, pong frames should echo the ping payload.
     *
     * @param data The pong payload (should match received ping)
     * @return Buffer containing the serialized frame
     */
    suspend fun writePongFrame(data: ReadBuffer = EMPTY_BUFFER): ReadBuffer {
        val truncatedData = if (data.remaining() > 125) {
            data.readBytes(125)
        } else {
            data
        }
        return writeFrame(Opcode.Pong, truncatedData, fin = true, compress = false)
    }

    /**
     * Writes a frame with the given opcode and payload.
     *
     * @param opcode The frame opcode
     * @param payload The payload data
     * @param fin Whether this is the final fragment
     * @param compress Whether to attempt compression (ignored for control frames)
     * @return Buffer containing the serialized frame
     */
    suspend fun writeFrame(
        opcode: Opcode,
        payload: ReadBuffer,
        fin: Boolean = true,
        compress: Boolean = compressionEnabled,
    ): ReadBuffer {
        // Determine if we should compress
        val shouldCompress = compress && !opcode.isControlFrame() && payload.remaining() > 0
        var rsv1 = false
        val finalPayload: ReadBuffer

        if (shouldCompress && compressor != null) {
            val originalSize = payload.remaining()
            val chunks = compressWithStreamingCompressor(payload, compressor)
            val compressedSize = totalRemaining(chunks)

            if (compressedSize < originalSize) {
                rsv1 = true
                compressor.reset()
                finalPayload = combineChunks(chunks, AllocationZone.Heap)
            } else {
                // Compression didn't help, use original
                payload.resetForRead()
                compressor.reset()
                finalPayload = payload
            }
        } else if (shouldCompress) {
            // Fallback to one-shot compression
            val originalSize = payload.remaining()
            val compressed = payload.compressWebsocketBuffer()

            if (compressed.remaining() < originalSize) {
                rsv1 = true
                finalPayload = compressed
            } else {
                payload.resetForRead()
                finalPayload = payload
            }
        } else {
            finalPayload = payload
        }

        // Generate masking key for client frames
        val maskingKey = if (clientMode) {
            MaskingKey.FourByteMaskingKey()
        } else {
            MaskingKey.NoMaskingKey
        }

        return serializeFrame(
            fin = fin,
            rsv1 = rsv1,
            rsv2 = false,
            rsv3 = false,
            opcode = opcode,
            maskingKey = maskingKey,
            payload = finalPayload,
        )
    }

    /**
     * Serializes a frame to a buffer.
     */
    private fun serializeFrame(
        fin: Boolean,
        rsv1: Boolean,
        rsv2: Boolean,
        rsv3: Boolean,
        opcode: Opcode,
        maskingKey: MaskingKey,
        payload: ReadBuffer,
    ): ReadBuffer {
        val payloadSize = payload.remaining()

        // Calculate frame size
        val headerSize = 2 + when {
            payloadSize <= 125 -> 0
            payloadSize <= 65535 -> 2
            else -> 8
        } + if (maskingKey is MaskingKey.FourByteMaskingKey) 4 else 0

        val frameSize = headerSize + payloadSize
        val buffer = PlatformBuffer.allocate(frameSize, AllocationZone.Heap)

        // Byte 1: FIN, RSV1-3, opcode
        var byte1 = opcode.value.toInt() and 0x0F
        if (fin) byte1 = byte1 or 0x80
        if (rsv1) byte1 = byte1 or 0x40
        if (rsv2) byte1 = byte1 or 0x20
        if (rsv3) byte1 = byte1 or 0x10
        buffer.writeByte(byte1.toByte())

        // Byte 2: MASK, payload length
        val masked = maskingKey is MaskingKey.FourByteMaskingKey
        var byte2 = when {
            payloadSize <= 125 -> payloadSize
            payloadSize <= 65535 -> 126
            else -> 127
        }
        if (masked) byte2 = byte2 or 0x80
        buffer.writeByte(byte2.toByte())

        // Extended payload length
        when {
            payloadSize > 65535 -> buffer.writeLong(payloadSize.toLong())
            payloadSize > 125 -> buffer.writeShort(payloadSize.toShort())
        }

        // Masking key and payload
        if (maskingKey is MaskingKey.FourByteMaskingKey) {
            buffer.writeInt(maskingKey.packed)
            val payloadStart = buffer.position()
            payload.position(0)
            buffer.write(payload)

            // Apply XOR mask in-place
            val payloadEnd = buffer.position()
            buffer.position(payloadStart)
            buffer.setLimit(payloadEnd)
            buffer.xorMask(maskingKey.packed)
            buffer.position(payloadEnd)
            buffer.setLimit(buffer.capacity)
        } else {
            payload.position(0)
            buffer.write(payload)
        }

        buffer.resetForRead()
        return buffer
    }
}
