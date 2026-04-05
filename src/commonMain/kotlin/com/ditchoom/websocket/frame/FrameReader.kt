package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.PeekResult
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
    val raw: UByte,
) {
    /** FIN bit - indicates final fragment */
    inline val fin: Boolean get() = (raw.toInt() and 0x80) != 0

    /** RSV1 bit - used by extensions (e.g., compression) */
    inline val rsv1: Boolean get() = (raw.toInt() and 0x40) != 0

    /** RSV2 bit - reserved */
    inline val rsv2: Boolean get() = (raw.toInt() and 0x20) != 0

    /** RSV3 bit - reserved */
    inline val rsv3: Boolean get() = (raw.toInt() and 0x10) != 0

    /** Frame opcode */
    inline val opcode: Opcode get() = Opcode.fromInt(raw.toInt() and 0x0F)

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
            return FrameHeaderByte1(byte1.toUByte())
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
    private val pool: BufferPool? = null,
) {
    /**
     * Attempts to read a complete frame from the processor.
     *
     * Uses a two-layer architecture:
     * 1. **Streaming layer** (this method): peeks at bytes to determine frame size,
     *    verifies the complete frame is available, then reads header + payload buffers.
     * 2. **Codec layer** ([WsFrameHeaderCodec]): synchronously decodes the header from
     *    a complete buffer — handles variable-width length and conditional masking key.
     *
     * @return A parsed frame, or null if not enough data is available
     * @throws FrameParseException if the frame data is malformed
     */
    suspend fun readFrame(): ParsedFrame? {
        // Use the generated codec to determine header size from the stream
        val headerSize =
            when (val peek = WsFrameHeaderCodec.peekFrameSize(processor, 0)) {
                is PeekResult.NeedsMoreData -> return null
                is PeekResult.Size -> peek.bytes
            }

        // Peek the payload length to verify complete frame is available
        val byte2 = processor.peekByte(1).toInt() and 0xFF
        val len7 = byte2 and 0x7F
        val payloadLength = peekPayloadLength(len7) ?: return null
        val totalFrameSize = headerSize + payloadLength
        if (processor.available() < totalFrameSize) return null

        // Read header bytes and decode with codec (parses length + mask in one pass)
        val headerBuffer = processor.readBuffer(headerSize)
        val header = WsFrameHeaderCodec.decode(headerBuffer)

        // Read and unmask payload
        val payload = readPayload(payloadLength, header.maskingKey)

        // Dispatch by opcode
        return buildParsedFrame(header, payloadLength, payload)
    }

    /**
     * Peeks the actual payload length from the stream without consuming bytes.
     * Returns null if not enough data is available for the extended length.
     */
    private suspend fun peekPayloadLength(len7: Int): Int? =
        when (len7) {
            126 -> {
                if (processor.available() < 4) return null
                processor.peekShort(2).toInt() and 0xFFFF
            }
            127 -> {
                if (processor.available() < 10) return null
                val len64 = processor.peekLong(2)
                if (len64 > Int.MAX_VALUE || len64 < 0) {
                    throw FrameParseException("Payload length out of range: $len64")
                }
                len64.toInt()
            }
            else -> len7
        }

    /**
     * Reads payload bytes and applies XOR unmasking if masked.
     */
    private suspend fun readPayload(
        payloadLength: Int,
        maskingKey: WsMaskingKey?,
    ): ReadBuffer {
        if (payloadLength == 0) return ReadBuffer.EMPTY_BUFFER

        val buffer = processor.readBuffer(payloadLength)
        if (maskingKey == null) return buffer

        // Unmask in-place (SIMD-optimized)
        val writableBuffer =
            if (buffer is ReadWriteBuffer) {
                buffer
            } else {
                val copy = pool?.acquire(payloadLength) ?: BufferFactory.Default.allocate(payloadLength)
                copy.write(buffer)
                copy.resetForRead()
                copy
            }
        val payloadStart = writableBuffer.position()
        writableBuffer.xorMask(maskingKey.raw.toInt())
        writableBuffer.position(payloadStart)
        return writableBuffer
    }

    /**
     * Constructs the appropriate [ParsedFrame] subtype based on opcode.
     */
    private fun buildParsedFrame(
        header: WsFrameHeader,
        payloadLength: Int,
        payload: ReadBuffer,
    ): ParsedFrame {
        val byte1 = header.byte1
        return when (byte1.opcode) {
            Opcode.Text -> ParsedFrame.DataFrame.Text(byte1, payloadLength, payload)
            Opcode.Binary -> ParsedFrame.DataFrame.Binary(byte1, payloadLength, payload)
            Opcode.Continuation -> ParsedFrame.DataFrame.Continuation(byte1, payloadLength, payload)
            Opcode.Ping -> ParsedFrame.ControlFrame.Ping(byte1, payloadLength, payload)
            Opcode.Pong -> ParsedFrame.ControlFrame.Pong(byte1, payloadLength, payload)
            Opcode.Close -> parseCloseFrame(byte1, payloadLength, payload)
            // Reserved opcodes (0x3-0x7 non-control, 0xB-0xF control) per RFC 6455
            Opcode.ReservedBit3, Opcode.ReservedBit4, Opcode.ReservedBit5,
            Opcode.ReservedBit6, Opcode.ReservedBit7, Opcode.ReservedBitB,
            Opcode.ReservedBitC, Opcode.ReservedBitD, Opcode.ReservedBitE,
            Opcode.ReservedBitF,
            ->
                ParsedFrame.InvalidFrame(
                    byte1,
                    payloadLength,
                    payload,
                    "Reserved opcode ${byte1.opcode} is not allowed",
                )
        }
    }

    /**
     * Parses a close frame, extracting status code and reason from payload.
     */
    private fun parseCloseFrame(
        byte1: FrameHeaderByte1,
        payloadLength: Int,
        payload: ReadBuffer,
    ): ParsedFrame.ControlFrame.Close {
        if (payloadLength == 0) {
            return ParsedFrame.ControlFrame.Close(byte1, 0, payload, CloseCode.NO_STATUS_RECEIVED)
        }
        if (payloadLength == 1) {
            return ParsedFrame.ControlFrame.Close(
                byte1,
                1,
                payload,
                CloseCode.PROTOCOL_ERROR,
                "Invalid close payload length",
            )
        }
        val savedPos = payload.position()
        var hasInvalidUtf8 = false
        val code = CloseCode(payload.readUnsignedShort())
        val reason =
            if (payload.hasRemaining()) {
                try {
                    payload.readString(payload.remaining(), Charset.UTF8)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    hasInvalidUtf8 = true
                    ""
                }
            } else {
                ""
            }
        payload.position(savedPos)
        return ParsedFrame.ControlFrame.Close(byte1, payloadLength, payload, code, reason, hasInvalidUtf8)
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
 * Access fields via header1.fin, header1.rsv1, header1.opcode, etc.
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

    /** Actual payload length (extended if needed) */
    val payloadLength: Int

    /** The payload data (already unmasked if was masked) */
    val payload: ReadBuffer

    /** Whether this is a control frame (close, ping, pong) */
    val isControlFrame: Boolean get() = this is ControlFrame

    /** Whether this is a data frame (text, binary, continuation) */
    val isDataFrame: Boolean get() = this is DataFrame

    // Convenience accessors that delegate to header byte
    val fin: Boolean get() = header1.fin
    val rsv1: Boolean get() = header1.rsv1
    val rsv2: Boolean get() = header1.rsv2
    val rsv3: Boolean get() = header1.rsv3
    val opcode: Opcode get() = header1.opcode

    sealed interface DataFrame : ParsedFrame {
        data class Text(
            override val header1: FrameHeaderByte1,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : DataFrame

        data class Binary(
            override val header1: FrameHeaderByte1,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : DataFrame

        data class Continuation(
            override val header1: FrameHeaderByte1,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : DataFrame
    }

    data class InvalidFrame(
        override val header1: FrameHeaderByte1,
        override val payloadLength: Int,
        override val payload: ReadBuffer,
        val reason: String,
    ) : ParsedFrame

    sealed interface ControlFrame : ParsedFrame {
        data class Ping(
            override val header1: FrameHeaderByte1,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : ControlFrame

        data class Pong(
            override val header1: FrameHeaderByte1,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
        ) : ControlFrame

        data class Close(
            override val header1: FrameHeaderByte1,
            override val payloadLength: Int,
            override val payload: ReadBuffer,
            val closeCode: CloseCode = CloseCode.NO_STATUS_RECEIVED,
            val closeReason: String = "",
            val hasInvalidUtf8: Boolean = false,
        ) : ControlFrame
    }
}

/**
 * Exception thrown when frame parsing fails.
 */
class FrameParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
