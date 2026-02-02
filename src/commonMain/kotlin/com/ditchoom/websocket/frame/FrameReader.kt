package com.ditchoom.websocket.frame

import com.ditchoom.buffer.Charset
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
 *
 * Per RFC 6455 Section 5.2:
 * ```
 *  0 1 2 3 4 5 6 7
 * +-+-+-+-+-------+
 * |F|R|R|R| opcode|
 * |I|S|S|S|  (4)  |
 * |N|V|V|V|       |
 * | |1|2|3|       |
 * +-+-+-+-+-------+
 * ```
 */
@JvmInline
value class FrameHeaderByte1(
    val packed: Int,
) {
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

    companion object {
        /** Pack header byte 1 from individual flags - zero allocation */
        fun pack(
            fin: Boolean,
            rsv1: Boolean,
            rsv2: Boolean,
            rsv3: Boolean,
            opcode: Opcode,
        ): FrameHeaderByte1 {
            var byte1 = opcode.value.toInt() and 0x0F
            if (fin) byte1 = byte1 or 0x80
            if (rsv1) byte1 = byte1 or 0x40
            if (rsv2) byte1 = byte1 or 0x20
            if (rsv3) byte1 = byte1 or 0x10
            return FrameHeaderByte1(byte1)
        }
    }
}

/**
 * Packed representation of frame header byte 2.
 * Zero-allocation access to MASK and payload length fields.
 *
 * Per RFC 6455 Section 5.2:
 * ```
 *  0 1 2 3 4 5 6 7
 * +-+-------------+
 * |M| Payload len |
 * |A|     (7)     |
 * |S|             |
 * |K|             |
 * +-+-------------+
 * ```
 */
@JvmInline
value class FrameHeaderByte2(
    val packed: Int,
) {
    /** MASK bit - indicates payload is masked */
    inline val masked: Boolean get() = (packed and 0x80) != 0

    /** 7-bit payload length (may indicate extended length follows) */
    inline val payloadLen7: Int get() = packed and 0x7F

    companion object {
        /** Create header byte 2 for given payload length (unmasked) - zero allocation */
        fun forPayload(
            length: Int,
            masked: Boolean = false,
        ): FrameHeaderByte2 {
            val len7 =
                when {
                    length <= 125 -> length
                    length <= 65535 -> 126
                    else -> 127
                }
            val byte2 = if (masked) len7 or 0x80 else len7
            return FrameHeaderByte2(byte2)
        }
    }
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
        val (headerSize, payloadLength) =
            calculateLengths(header2.payloadLen7, header2.masked)
                ?: return null // Not enough data for extended length

        // Check if we have the complete frame
        val totalFrameSize = headerSize + payloadLength
        if (processor.available() < totalFrameSize) return null

        // Now consume the header and read the frame
        processor.skip(2) // Skip first two bytes we already peeked

        // Read extended payload length if present
        val actualPayloadLength =
            when (header2.payloadLen7) {
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
        val maskingKey =
            if (header2.masked) {
                MaskingKey.FourByteMaskingKey(processor.readInt())
            } else {
                MaskingKey.NoMaskingKey
            }

        // Read payload data (zero-copy when possible via processor.readBuffer)
        val payload =
            if (actualPayloadLength > 0) {
                val buffer = processor.readBuffer(actualPayloadLength)
                // Unmask if needed (note: servers never send masked frames per RFC 6455)
                if (maskingKey is MaskingKey.FourByteMaskingKey) {
                    // Try in-place modification if buffer is writable, otherwise copy
                    val writableBuffer =
                        if (buffer is ReadWriteBuffer) {
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

        // Construct the appropriate concrete frame type based on opcode
        return when (header1.opcode) {
            Opcode.Text -> ParsedFrame.DataFrame.Text(header1, header2, actualPayloadLength, payload)
            Opcode.Binary -> ParsedFrame.DataFrame.Binary(header1, header2, actualPayloadLength, payload)
            Opcode.Continuation -> ParsedFrame.DataFrame.Continuation(header1, header2, actualPayloadLength, payload)
            Opcode.Ping -> ParsedFrame.ControlFrame.Ping(header1, header2, actualPayloadLength, payload)
            Opcode.Pong -> ParsedFrame.ControlFrame.Pong(header1, header2, actualPayloadLength, payload)
            Opcode.Close -> {
                // Parse close code and reason from payload
                val result = parseClosePayloadInternal(payload, actualPayloadLength)
                ParsedFrame.ControlFrame.Close(
                    header1,
                    header2,
                    actualPayloadLength,
                    payload,
                    result.code,
                    result.reason,
                    result.hasInvalidUtf8,
                )
            }
            else -> {
                // Reserved opcodes (0x3-0x7 non-control, 0xB-0xF control) are invalid per RFC 6455
                ParsedFrame.InvalidFrame(
                    header1,
                    header2,
                    actualPayloadLength,
                    payload,
                    "Reserved opcode ${header1.opcode} is not allowed",
                )
            }
        }
    }

    /**
     * Result of parsing a close frame payload.
     */
    private data class ClosePayloadParseResult(
        val code: CloseCode,
        val reason: String,
        val hasInvalidUtf8: Boolean,
    )

    /**
     * Internal close payload parser for FrameReader.
     */
    private fun parseClosePayloadInternal(
        payload: ReadBuffer,
        payloadLength: Int,
    ): ClosePayloadParseResult {
        if (payloadLength == 0) {
            return ClosePayloadParseResult(CloseCode.NO_STATUS_RECEIVED, "", false)
        }

        val savedPos = payload.position()

        if (payloadLength == 1) {
            payload.position(savedPos)
            return ClosePayloadParseResult(CloseCode.PROTOCOL_ERROR, "Invalid close payload length", false)
        }

        val code = CloseCode(payload.readUnsignedShort())
        var hasInvalidUtf8 = false
        val reason =
            if (payload.hasRemaining()) {
                try {
                    payload.readString(payload.remaining(), Charset.UTF8)
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    // Use Throwable to catch JS TypeError from TextDecoder
                    hasInvalidUtf8 = true
                    ""
                }
            } else {
                ""
            }

        payload.position(savedPos)
        return ClosePayloadParseResult(code, reason, hasInvalidUtf8)
    }

    /**
     * Calculates header size and payload length based on the 7-bit length field.
     *
     * @return Pair of (headerSize, payloadLength) or null if not enough data
     */
    private suspend fun calculateLengths(
        payloadLen7: Int,
        masked: Boolean,
    ): Pair<Int, Int>? {
        // Base header is 2 bytes
        var headerSize = 2

        // Extended payload length
        val payloadLength =
            when (payloadLen7) {
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
 * Sealed interface for parsed incoming WebSocket frames.
 *
 * Uses value classes for header bytes - preserves wire format with zero allocation overhead.
 * Access fields via header1.fin, header1.rsv1, header1.opcode, header2.masked, etc.
 *
 * This sealed interface hierarchy enables exhaustive pattern matching in Kotlin,
 * eliminating runtime opcode checks and improving type safety.
 *
 * ## RFC 6455 Section 5.2 - Base Framing Protocol
 * https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
 */
sealed interface ParsedFrame {
    /** First header byte - packs FIN, RSV1-3, opcode (inline accessors) */
    val header1: FrameHeaderByte1

    /** Second header byte - packs MASK flag, payloadLen7 (inline accessors) */
    val header2: FrameHeaderByte2

    /** Actual payload length (extended if needed) */
    val payloadLength: Int

    /** The payload data (already unmasked if was masked) */
    val payload: ReadBuffer

    /** Whether this is a control frame (close, ping, pong) */
    val isControlFrame: Boolean get() = this is ControlFrame

    /** Whether this is a data frame (text, binary, continuation) */
    val isDataFrame: Boolean get() = this is DataFrame

    // Convenience accessors that delegate to header bytes
    val fin: Boolean get() = header1.fin
    val rsv1: Boolean get() = header1.rsv1
    val rsv2: Boolean get() = header1.rsv2
    val rsv3: Boolean get() = header1.rsv3
    val opcode: Opcode get() = header1.opcode
    val masked: Boolean get() = header2.masked

    /**
     * Data frames (text, binary, continuation) per RFC 6455 Section 5.6.
     */
    sealed interface DataFrame : ParsedFrame {
        /**
         * Text frame (opcode 0x1).
         * Payload is UTF-8 encoded text.
         */
        data class Text(
            override val header1: FrameHeaderByte1,
            override val header2: FrameHeaderByte2,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : DataFrame

        /**
         * Binary frame (opcode 0x2).
         * Payload is arbitrary binary data.
         */
        data class Binary(
            override val header1: FrameHeaderByte1,
            override val header2: FrameHeaderByte2,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : DataFrame

        /**
         * Continuation frame (opcode 0x0).
         * Used for message fragmentation per RFC 6455 Section 5.4.
         */
        data class Continuation(
            override val header1: FrameHeaderByte1,
            override val header2: FrameHeaderByte2,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : DataFrame
    }

    /**
     * Invalid frame with reserved opcode per RFC 6455.
     * Reserved opcodes are 0x3-0x7 (non-control) and 0xB-0xF (control).
     * These frames should be rejected with close code 1002 (Protocol Error).
     */
    data class InvalidFrame(
        override val header1: FrameHeaderByte1,
        override val header2: FrameHeaderByte2,
        override val payloadLength: Int,
        override val payload: ReadBuffer,
        val reason: String,
    ) : ParsedFrame

    /**
     * Control frames per RFC 6455 Section 5.5.
     * Control frames have opcodes 0x8-0xF and MUST have payload <= 125 bytes.
     */
    sealed interface ControlFrame : ParsedFrame {
        /**
         * Ping frame (opcode 0x9).
         * Per RFC 6455 Section 5.5.2, must be responded to with Pong.
         */
        data class Ping(
            override val header1: FrameHeaderByte1,
            override val header2: FrameHeaderByte2,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : ControlFrame

        /**
         * Pong frame (opcode 0xA).
         * Per RFC 6455 Section 5.5.3, sent in response to Ping.
         */
        data class Pong(
            override val header1: FrameHeaderByte1,
            override val header2: FrameHeaderByte2,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : ControlFrame

        /**
         * Close frame (opcode 0x8).
         * Per RFC 6455 Section 5.5.1, initiates connection close.
         *
         * @property closeCode The close status code (1005 NO_STATUS_RECEIVED if not present in payload)
         * @property closeReason Optional human-readable reason (UTF-8)
         * @property hasInvalidUtf8 True if the close reason contained invalid UTF-8 bytes
         */
        data class Close(
            override val header1: FrameHeaderByte1,
            override val header2: FrameHeaderByte2,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
            val closeCode: CloseCode = CloseCode.NO_STATUS_RECEIVED,
            val closeReason: String = "",
            val hasInvalidUtf8: Boolean = false,
        ) : ControlFrame
    }
}

/**
 * Factory function to create ParsedFrame instances (for backwards compatibility).
 * Prefer using the concrete types directly when possible.
 */
@Suppress("FunctionName")
fun ParsedFrame(
    fin: Boolean,
    rsv1: Boolean,
    rsv2: Boolean,
    rsv3: Boolean,
    opcode: Opcode,
    masked: Boolean,
    payloadLength: Int,
    payload: ReadBuffer,
): ParsedFrame {
    // Construct header bytes from individual flags
    var byte1 = opcode.value.toInt() and 0x0F
    if (fin) byte1 = byte1 or 0x80
    if (rsv1) byte1 = byte1 or 0x40
    if (rsv2) byte1 = byte1 or 0x20
    if (rsv3) byte1 = byte1 or 0x10
    val header1 = FrameHeaderByte1(byte1)

    var byte2 = payloadLength.coerceAtMost(127) and 0x7F
    if (masked) byte2 = byte2 or 0x80
    val header2 = FrameHeaderByte2(byte2)

    return when (opcode) {
        Opcode.Text -> ParsedFrame.DataFrame.Text(header1, header2, payloadLength, payload)
        Opcode.Binary -> ParsedFrame.DataFrame.Binary(header1, header2, payloadLength, payload)
        Opcode.Continuation -> ParsedFrame.DataFrame.Continuation(header1, header2, payloadLength, payload)
        Opcode.Ping -> ParsedFrame.ControlFrame.Ping(header1, header2, payloadLength, payload)
        Opcode.Pong -> ParsedFrame.ControlFrame.Pong(header1, header2, payloadLength, payload)
        Opcode.Close -> {
            // Parse close code and reason from payload if present
            val result = parseClosePayload(payload, payloadLength)
            ParsedFrame.ControlFrame.Close(
                header1,
                header2,
                payloadLength,
                payload,
                result.code,
                result.reason,
                result.hasInvalidUtf8,
            )
        }
        else -> {
            // Reserved opcodes (0x3-0x7 non-control, 0xB-0xF control) are invalid per RFC 6455
            ParsedFrame.InvalidFrame(
                header1,
                header2,
                payloadLength,
                payload,
                "Reserved opcode $opcode is not allowed",
            )
        }
    }
}

/**
 * Result of parsing a close frame payload.
 */
private data class ClosePayloadResult(
    val code: CloseCode,
    val reason: String,
    val hasInvalidUtf8: Boolean,
)

/**
 * Parses close code and reason from a close frame payload.
 */
private fun parseClosePayload(
    payload: ReadBuffer,
    payloadLength: Int,
): ClosePayloadResult {
    if (payloadLength == 0) {
        return ClosePayloadResult(CloseCode.NO_STATUS_RECEIVED, "", false)
    }

    // Save position for restoration
    val savedPos = payload.position()

    if (payloadLength == 1) {
        // Invalid: close frame with only 1 byte
        payload.position(savedPos)
        return ClosePayloadResult(CloseCode.PROTOCOL_ERROR, "Invalid close payload length", false)
    }

    val code = CloseCode(payload.readUnsignedShort())
    var hasInvalidUtf8 = false
    val reason =
        if (payload.hasRemaining()) {
            try {
                payload.readString(payload.remaining(), Charset.UTF8)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Use Throwable to catch JS TypeError from TextDecoder
                hasInvalidUtf8 = true
                ""
            }
        } else {
            ""
        }

    // Restore position for consumers who want to read the raw payload
    payload.position(savedPos)

    return ClosePayloadResult(code, reason, hasInvalidUtf8)
}

/**
 * Exception thrown when frame parsing fails.
 */
class FrameParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
