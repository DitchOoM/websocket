package com.ditchoom.websocket.frame

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

/**
 * Byte 2 of a WebSocket frame header, packed as a value class.
 *
 * ```
 * +-+-------------+
 * |M| Payload len |
 * |A|     (7)     |
 * |S|             |
 * |K|             |
 * +-+-------------+
 * ```
 *
 * The processor reads this as a single UByte. The boolean properties drive
 * `@WhenTrue` conditionals on the extended length and masking key fields.
 */
@JvmInline
value class WsHeaderByte2(
    val raw: UByte,
) {
    /** MASK bit — client frames must be masked per RFC 6455 */
    inline val masked: Boolean get() = (raw.toInt() and 0x80) != 0

    /** 7-bit length indicator */
    inline val lengthIndicator: Int get() = raw.toInt() and 0x7F

    /** True when lengthIndicator == 126 → next 2 bytes are the real length */
    inline val extended16: Boolean get() = lengthIndicator == 126

    /** True when lengthIndicator == 127 → next 8 bytes are the real length */
    inline val extended64: Boolean get() = lengthIndicator == 127

    companion object {
        /** Pack byte2 from payload size and mask flag — zero allocation */
        fun pack(
            payloadSize: Long,
            masked: Boolean,
        ): WsHeaderByte2 {
            val maskBit = if (masked) 0x80 else 0
            val len7 =
                when {
                    payloadSize <= 125 -> payloadSize.toInt()
                    payloadSize <= 65535 -> 126
                    else -> 127
                }
            return WsHeaderByte2((maskBit or len7).toUByte())
        }
    }
}

/**
 * WebSocket frame header per RFC 6455 Section 5.2.
 *
 * All fields are fixed-size primitives or `@WhenTrue` conditionals on value class
 * properties, so the KSP processor can generate `encode`, `decode`, `peekFrameSize`,
 * and `sizeOf` automatically — no custom codec needed.
 *
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
 * | Masking-key (continued)       |
 * +-------------------------------+
 * ```
 */
@ProtocolMessage
data class WsFrameHeader(
    val byte1: FrameHeaderByte1,
    val byte2: WsHeaderByte2,
    @WhenTrue("byte2.extended16") val extendedLength16: UShort? = null,
    @WhenTrue("byte2.extended64") val extendedLength64: Long? = null,
    @WhenTrue("byte2.masked") val maskingKey: WsMaskingKey? = null,
) {
    /** Resolved payload length regardless of encoding */
    val payloadLength: Long
        get() = extendedLength64 ?: extendedLength16?.toLong() ?: byte2.lengthIndicator.toLong()

    /** Whether the frame is masked */
    val masked: Boolean get() = byte2.masked

    /** Header wire size in bytes: byte1(1) + byte2(1) + extLen(0|2|8) + mask(0|4) */
    val wireSize: Int
        get() = 2 + (if (extendedLength16 != null) 2 else 0) +
            (if (extendedLength64 != null) 8 else 0) +
            (if (maskingKey != null) 4 else 0)

    companion object {
        /** Build a header from logical parameters — zero allocation */
        fun build(
            byte1: FrameHeaderByte1,
            payloadSize: Long,
            masked: Boolean,
            maskingKey: WsMaskingKey? = null,
        ): WsFrameHeader {
            val byte2 = WsHeaderByte2.pack(payloadSize, masked)
            return WsFrameHeader(
                byte1 = byte1,
                byte2 = byte2,
                extendedLength16 = if (byte2.extended16) payloadSize.toUShort() else null,
                extendedLength64 = if (byte2.extended64) payloadSize else null,
                maskingKey = maskingKey,
            )
        }
    }
}

/**
 * 4-byte masking key packed as UInt for zero-allocation access.
 */
@JvmInline
value class WsMaskingKey(
    val raw: UInt,
)

/**
 * WebSocket close frame body: 2-byte status code + UTF-8 reason string.
 */
@ProtocolMessage
data class WsCloseBody(
    val statusCode: CloseCode,
    @RemainingBytes val reason: String,
)
