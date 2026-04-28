package com.ditchoom.websocket.frame

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.When
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
 * `@When` conditionals on the extended length and masking key fields.
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
 * All fields are fixed-size primitives or `@When` conditionals on value class
 * properties, so the KSP processor can generate `encode`, `decode`, `peekFrameSize`,
 * and `sizeOf` automatically — no custom codec needed.
 *
 * Also serves as the `@DispatchOn` discriminator for [WsFrame]: the generated
 * [WsFrameCodec] reads the full header, extracts [opcodeValue], and dispatches
 * to the correct variant codec.
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
    @When("byte2.extended16") val extendedLength16: UShort? = null,
    @When("byte2.extended64") val extendedLength64: Long? = null,
    @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
) {
    /** Resolved payload length regardless of encoding */
    val payloadLength: Long
        get() = extendedLength64 ?: extendedLength16?.toLong() ?: byte2.lengthIndicator.toLong()

    /** Whether the frame is masked */
    val masked: Boolean get() = byte2.masked

    /** Header wire size in bytes: byte1(1) + byte2(1) + extLen(0|2|8) + mask(0|4) */
    val wireSize: Int
        get() =
            2 + (if (extendedLength16 != null) 2 else 0) +
                (if (extendedLength64 != null) 8 else 0) +
                (if (maskingKey != null) 4 else 0)

    /** Opcode as Int for sealed dispatch matching via @DispatchOn */
    @DispatchValue
    val opcodeValue: Int get() = byte1.raw.toInt() and 0x0F

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

// ──────────────────────── WsFrame sealed dispatch ────────────────────────

/**
 * WebSocket frame dispatched by opcode via [WsFrameHeader].
 *
 * KSP generates `WsFrameCodec` which reads the header, extracts
 * [WsFrameHeader.opcodeValue], and dispatches to the correct variant.
 *
 * Payload-bearing variants ([Data]) use `@Payload T` — the consumer provides
 * a decode lambda so the frame carries the consumer's type, not a raw buffer.
 * This is zero-copy (reads directly from buffer), type-safe, and free from
 * impossible states (no buffer reference stored in the model).
 *
 * [Data] is a sealed sub-interface giving typed `.payload` access without
 * casting when the assembler handles data/control frames.
 */
@DispatchOn(WsFrameHeader::class, framing = WsFraming::class)
@ProtocolMessage
sealed interface WsFrame {
    val header: WsFrameHeader

    @PacketType(0x1)
    @ProtocolMessage
    data class Text<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0x2)
    @ProtocolMessage
    data class Binary<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0x0)
    @ProtocolMessage
    data class Continuation<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0x8)
    @ProtocolMessage
    data class Close(
        override val header: WsFrameHeader,
        @When("remaining >= 2") val body: WsCloseBody? = null,
    ) : WsFrame

    @PacketType(0x9)
    @ProtocolMessage
    data class Ping<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0xA)
    @ProtocolMessage
    data class Pong<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame
}

/**
 * Exception thrown when frame parsing fails.
 */
class FrameParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
