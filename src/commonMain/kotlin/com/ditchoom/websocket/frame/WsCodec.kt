package com.ditchoom.websocket.frame

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
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
@ProtocolMessage
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

    /** Frame opcode (typed enum for application code). */
    inline val opcode: Opcode get() = Opcode.fromInt(raw.toInt() and 0x0F)

    /**
     * Opcode as `Int` — the [DispatchValue] the generated [WsFrameCodec] reads to
     * pick a sealed variant. Mirrors RFC 6455 §5.2 low-nibble dispatch.
     */
    @DispatchValue
    val opcodeValue: Int get() = raw.toInt() and 0x0F

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
@ProtocolMessage
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
 * WebSocket frame header per RFC 6455 §5.2 — standalone `@ProtocolMessage` for
 * header-shape isolation tests and for connection-layer code that peeks the
 * variable-length header before invoking the variant codec ([com.ditchoom.websocket.frame.WsFraming]).
 * Does NOT participate in the [WsFrame] sealed dispatch — the dispatcher reads
 * [FrameHeaderByte1] only and routes to a variant that re-reads the full header
 * structure inline.
 *
 * The duplication between this struct and the per-variant inline header fields is
 * the cost of the value-class discriminator constraint on `@DispatchOn` in this
 * snapshot. When the data-class-discriminator + `framing=` form lands upstream, the
 * variants can collapse back to `header: WsFrameHeader`.
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
@ProtocolMessage(wireOrder = Endianness.Big)
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

    /** Opcode as Int — same value [FrameHeaderByte1.opcodeValue] surfaces for dispatch. */
    val opcodeValue: Int get() = byte1.opcodeValue

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
@ProtocolMessage
value class WsMaskingKey(
    val raw: UInt,
)

/**
 * WebSocket close frame body per RFC 6455 §5.5.1 / §7.4.1: 2-byte BE status code
 * followed by an optional UTF-8 reason that fills the remainder of the buffer.
 * The parent [WsFrame.Close] variant must have already bounded the buffer to the
 * close-payload extent — [reason] absorbs everything after the status code.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class WsCloseBody(
    val statusCode: CloseCode,
    @RemainingBytes val reason: String,
)

// ──────────────────────── WsFrame sealed dispatch ────────────────────────

/**
 * WebSocket frame, sealed dispatched on opcode (low nibble of [FrameHeaderByte1.raw]).
 *
 * Reserved opcodes 0x3-0x7 (non-control) and 0xB-0xF (control) intentionally have no
 * `@PacketType` — the dispatcher rejects them at decode, mirroring RFC 6455 §5.2's
 * protocol-error rule.
 *
 * ### Payload shape — `<out P : Payload>` parent, `<P : Payload>` data variants
 *
 * `@ProtocolMessage` model fields must not be `ByteArray`/`ReadBuffer`/`PlatformBuffer`
 * — a buffer field forces a copy at the codec boundary, taking the choice away from
 * the consumer (zero-copy is the easy path; copies are explicit). So data-bearing
 * variants ([Text], [Binary], [Continuation], [Ping], [Pong]) carry `<P : Payload>` +
 * `@RemainingBytes val payload: P`; the consumer supplies a `Codec<P>` to the
 * constructor-injected [WsFrameCodec] and the framework decodes payload bytes
 * directly into the consumer's typed model. [Close] has no generic payload (only the
 * structured [WsCloseBody]) and extends `WsFrame<Nothing>`.
 *
 * Each variant inlines the full header structure (byte1 + byte2 + optional ext lengths
 * + optional masking key) before its payload. The duplication with [WsFrameHeader]
 * is the cost of the value-class discriminator constraint; when the data-class +
 * `framing=` form lands upstream, the variants can collapse to `header: WsFrameHeader`.
 *
 * ### Consumer instantiation
 *
 * ```kotlin
 * val textCodec = WsFrameCodec(TextPayloadCodec)            // decode Text → TextPayload
 * val binaryCodec = WsFrameCodec(MyApplicationPayloadCodec) // decode Binary → app type
 * ```
 *
 * Wire bytes are identical across instantiations; only the payload decoder differs.
 */
@DispatchOn(FrameHeaderByte1::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface WsFrame<out P : Payload> {
    /**
     * Text frame (opcode 0x1). RFC 6455 §5.6 — payload bytes are UTF-8 application data.
     * Per-frame UTF-8 validation is the consumer-codec's responsibility (a `Codec<T>`
     * that calls `buffer.readString(remaining, Charset.UTF8)` throws on malformed input);
     * fragmented-text reassembly across [Continuation] frames lives in
     * [com.ditchoom.websocket.frame.MessageAssembler], not at the codec layer.
     */
    @PacketType(0x1)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Text<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /** Binary frame (opcode 0x2). RFC 6455 §5.6 — opaque application bytes. */
    @PacketType(0x2)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Binary<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /** Continuation frame (opcode 0x0). RFC 6455 §5.4 fragment payload. */
    @PacketType(0x0)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Continuation<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /** Close frame (opcode 0x8). Body absent for empty payload, present when ≥2 bytes follow. */
    @PacketType(0x8)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Close(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @When("remaining >= 2") val body: WsCloseBody? = null,
    ) : WsFrame<Nothing>

    /** Ping frame (opcode 0x9). RFC 6455 §5.5.2 — payload is application data. */
    @PacketType(0x9)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Ping<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /** Pong frame (opcode 0xA). RFC 6455 §5.5.3 — must echo the corresponding Ping's payload. */
    @PacketType(0xA)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Pong<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>
}

/**
 * Exception thrown when frame parsing fails.
 */
class FrameParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
