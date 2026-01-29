package com.ditchoom.websocket.frame

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.stream.SuspendingStreamProcessor
import com.ditchoom.websocket.MaskingKey
import com.ditchoom.websocket.Opcode
import kotlin.jvm.JvmInline

/**
 * Packed representation of frame header byte 1.
 * Zero-allocation access to FIN, RSV1-3, and opcode fields.
 */
@JvmInline
internal value class FrameHeaderByte1(val packed: Int) {
    /** FIN bit - indicates final fragment */
    inline val fin: Boolean get() = (packed and 0x80) != 0
    /** RSV1 bit - used by extensions (e.g., compression) */
    inline val rsv1: Boolean get() = (packed and 0x40) != 0
    /** RSV2 bit - reserved */
    inline val rsv2: Boolean get() = (packed and 0x20) != 0
    /** RSV3 bit - reserved */
    inline val rsv3: Boolean get() = (packed and 0x10) != 0
    /** Frame opcode */
    inline val opcode: Opcode get() = Opcode.fromInt(packed and 0x0F)
}

/**
 * Packed representation of frame header byte 2.
 * Zero-allocation access to MASK and payload length fields.
 */
@JvmInline
internal value class FrameHeaderByte2(val packed: Int) {
    /** MASK bit - indicates payload is masked */
    inline val masked: Boolean get() = (packed and 0x80) != 0
    /** 7-bit payload length (may indicate extended length follows) */
    inline val payloadLen7: Int get() = packed and 0x7F
}

/**
 * Zero-copy WebSocket frame reader using StreamProcessor.
 *
 * Parses WebSocket frames per RFC 6455 Section 5.2 using the buffer library's
 * optimized peek operations. Supports incremental parsing - can be called
 * repeatedly as data arrives until a complete frame is available.
 *
 * ## RFC 6455 Section 5.2 - Base Framing Protocol
 * https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
 *
 * Frame format:
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * :                     Payload Data continued ...                :
 * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 * |                     Payload Data continued ...                |
 * +---------------------------------------------------------------+
 * ```
 *
 * ## Zero-Copy Design
 *
 * The reader uses StreamProcessor's peek operations to inspect frame headers
 * without consuming data until a complete frame is available. Payload data
 * is returned as a buffer slice when possible.
 */
class FrameReader(
    private val processor: SuspendingStreamProcessor,
) {
    /**
     * Attempts to read a complete frame from the processor.
     *
     * This method is non-blocking with respect to frame parsing - it returns
     * immediately if not enough data is available. Call this method after
     * appending more data to the processor.
     *
     * @return A parsed frame, or null if not enough data is available
     * @throws FrameParseException if the frame data is malformed
     */
    suspend fun readFrame(): ParsedFrame? {
        // Need at least 2 bytes for basic header
        if (processor.available() < 2) return null

        // Parse first two bytes as a short (more efficient than two peekByte calls)
        val headerShort = processor.peekShort(0).toInt() and 0xFFFF
        val header1 = FrameHeaderByte1((headerShort ushr 8) and 0xFF)
        val header2 = FrameHeaderByte2(headerShort and 0xFF)

        // Calculate header size and actual payload length
        val (headerSize, payloadLength) = calculateLengths(header2.payloadLen7, header2.masked)
            ?: return null // Not enough data for extended length

        // Check if we have the complete frame
        val totalFrameSize = headerSize + payloadLength
        if (processor.available() < totalFrameSize) return null

        // Now consume the header and read the frame
        processor.skip(2) // Skip first two bytes we already peeked

        // Read extended payload length if present
        val actualPayloadLength = when (header2.payloadLen7) {
            126 -> {
                processor.readShort().toInt() and 0xFFFF
            }
            127 -> {
                val len64 = processor.readLong()
                // RFC 6455 Section 5.2: "the most significant bit MUST be 0"
                if (len64 < 0) {
                    throw FrameParseException("Invalid payload length: MSB must be 0")
                }
                // For practical purposes, limit to Int.MAX_VALUE
                if (len64 > Int.MAX_VALUE) {
                    throw FrameParseException("Payload too large: $len64")
                }
                len64.toInt()
            }
            else -> header2.payloadLen7
        }

        // Read masking key if present
        val maskingKey = if (header2.masked) {
            MaskingKey.FourByteMaskingKey(processor.readInt())
        } else {
            MaskingKey.NoMaskingKey
        }

        // Read payload data (zero-copy when possible via processor.readBuffer)
        val payload = if (actualPayloadLength > 0) {
            val buffer = processor.readBuffer(actualPayloadLength)
            // Unmask if needed (note: servers never send masked frames per RFC 6455)
            if (maskingKey is MaskingKey.FourByteMaskingKey) {
                // Try in-place modification if buffer is writable, otherwise copy
                val writableBuffer = if (buffer is ReadWriteBuffer) {
                    buffer
                } else {
                    // Fallback: copy to writable buffer
                    val copy = PlatformBuffer.allocate(actualPayloadLength)
                    copy.write(buffer)
                    copy
                }
                // Apply SIMD-optimized XOR mask in-place
                writableBuffer.position(0)
                writableBuffer.setLimit(actualPayloadLength)
                writableBuffer.xorMask(maskingKey.packed)
                writableBuffer.position(0)
                writableBuffer
            } else {
                buffer
            }
        } else {
            ReadBuffer.EMPTY_BUFFER
        }

        return ParsedFrame(
            fin = header1.fin,
            rsv1 = header1.rsv1,
            rsv2 = header1.rsv2,
            rsv3 = header1.rsv3,
            opcode = header1.opcode,
            masked = header2.masked,
            payloadLength = actualPayloadLength,
            payload = payload,
        )
    }


    /**
     * Calculates header size and payload length based on the 7-bit length field.
     *
     * @return Pair of (headerSize, payloadLength) or null if not enough data
     */
    private suspend fun calculateLengths(payloadLen7: Int, masked: Boolean): Pair<Int, Int>? {
        // Base header is 2 bytes
        var headerSize = 2

        // Extended payload length
        val payloadLength = when (payloadLen7) {
            126 -> {
                // Need 2 more bytes for 16-bit length
                if (processor.available() < 4) return null
                headerSize += 2
                (processor.peekShort(2).toInt() and 0xFFFF)
            }
            127 -> {
                // Need 8 more bytes for 64-bit length
                if (processor.available() < 10) return null
                headerSize += 8
                val len64 = processor.peekLong(2)
                if (len64 > Int.MAX_VALUE || len64 < 0) {
                    throw FrameParseException("Payload length out of range: $len64")
                }
                len64.toInt()
            }
            else -> payloadLen7
        }

        // Add masking key size if present
        if (masked) {
            headerSize += 4
        }

        return headerSize to payloadLength
    }

    /**
     * Checks if enough data is available to read at least the frame header.
     *
     * This is a quick check that doesn't guarantee a complete frame is available,
     * but can be used to avoid calling readFrame() when clearly not enough data exists.
     */
    fun hasMinimumData(): Boolean = processor.available() >= 2
}

/**
 * A parsed WebSocket frame.
 *
 * This is the result of parsing a frame from the wire format. The payload
 * has already been unmasked if it was masked.
 *
 * @property fin FIN bit - indicates this is the final fragment
 * @property rsv1 RSV1 bit - used by extensions (e.g., compression)
 * @property rsv2 RSV2 bit - reserved for future use
 * @property rsv3 RSV3 bit - reserved for future use
 * @property opcode Frame opcode (text, binary, close, ping, pong, etc.)
 * @property masked Whether the frame was masked (client->server frames must be masked)
 * @property payloadLength The length of the payload in bytes
 * @property payload The payload data (already unmasked)
 */
data class ParsedFrame(
    val fin: Boolean,
    val rsv1: Boolean,
    val rsv2: Boolean,
    val rsv3: Boolean,
    val opcode: Opcode,
    val masked: Boolean,
    val payloadLength: Int,
    val payload: ReadBuffer,
) {
    /**
     * Whether this is a control frame (close, ping, pong).
     *
     * Per RFC 6455 Section 5.5, control frames have opcodes 0x8-0xF.
     */
    val isControlFrame: Boolean
        get() = opcode.isControlFrame()

    /**
     * Whether this is a data frame (text, binary, continuation).
     *
     * Per RFC 6455 Section 5.6, data frames have opcodes 0x0-0x7.
     */
    val isDataFrame: Boolean
        get() = !isControlFrame
}

/**
 * Exception thrown when frame parsing fails.
 */
class FrameParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
