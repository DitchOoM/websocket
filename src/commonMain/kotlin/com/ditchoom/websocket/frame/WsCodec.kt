package com.ditchoom.websocket.frame

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

/**
 * Wire-format length field for WebSocket frames.
 *
 * Encodes/decodes byte 2 (mask bit + 7-bit length indicator) plus the optional
 * extended payload length (16-bit or 64-bit) per RFC 6455 Section 5.2.
 */
data class WsFrameLength(
    val payloadLength: Long,
    val masked: Boolean,
) {
    init {
        require(payloadLength >= 0) { "Payload length must be non-negative, got $payloadLength" }
    }
}

/**
 * Custom codec for WebSocket's variable-width payload length encoding.
 *
 * The 7-bit length field in byte 2 is a 3-way discriminator:
 * - 0-125: length is the value itself (0 extra bytes)
 * - 126: next 2 bytes are a 16-bit unsigned length
 * - 127: next 8 bytes are a 64-bit unsigned length
 *
 * This is a standard custom codec — the framework doesn't need a `@Switch` annotation
 * because the conditional logic is encapsulated here.
 */
object WsFrameLengthCodec : Codec<WsFrameLength> {
    override fun decode(buffer: ReadBuffer): WsFrameLength {
        val byte2 = buffer.readByte().toInt() and 0xFF
        val masked = (byte2 and 0x80) != 0
        val len7 = byte2 and 0x7F
        val payloadLength =
            when (len7) {
                126 -> buffer.readShort().toLong() and 0xFFFF
                127 -> {
                    val len64 = buffer.readLong()
                    if (len64 < 0) {
                        throw FrameParseException("Invalid payload length: MSB must be 0")
                    }
                    len64
                }
                else -> len7.toLong()
            }
        return WsFrameLength(payloadLength, masked)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: WsFrameLength,
    ) {
        val len = value.payloadLength
        val maskBit = if (value.masked) 0x80 else 0
        when {
            len <= 125 -> buffer.writeByte((maskBit or len.toInt()).toByte())
            len <= 65535 -> {
                buffer.writeByte((maskBit or 126).toByte())
                buffer.writeShort(len.toShort())
            }
            else -> {
                buffer.writeByte((maskBit or 127).toByte())
                buffer.writeLong(len)
            }
        }
    }

    override fun sizeOf(value: WsFrameLength): Int =
        when {
            value.payloadLength <= 125 -> 1
            value.payloadLength <= 65535 -> 3
            else -> 9
        }

    /**
     * Returns the wire size of the length field given byte2's raw 7-bit length indicator.
     * Used by the streaming layer to determine header size without decoding.
     *
     * - 0-125: 1 byte (just byte2)
     * - 126: 3 bytes (byte2 + UShort)
     * - 127: 9 bytes (byte2 + ULong)
     */
    fun wireSize(payloadLen7: Int): Int =
        when (payloadLen7) {
            126 -> 3
            127 -> 9
            else -> 1
        }
}

/**
 * WebSocket frame header: byte 1 + variable-width length + optional masking key.
 *
 * Generated codec handles:
 * - `byte1`: auto-decoded as a value class wrapping UByte
 * - `length`: custom codec handles the 3-way payload length encoding
 * - `maskingKey`: conditionally present when `length.masked` is true
 */
@ProtocolMessage
data class WsFrameHeader(
    val byte1: FrameHeaderByte1,
    @UseCodec(WsFrameLengthCodec::class) val length: WsFrameLength,
    @WhenTrue("length.masked") val maskingKey: WsMaskingKey? = null,
) {
    init {
        require(length.masked == (maskingKey != null)) {
            "maskingKey must be non-null when masked=true and null when masked=false"
        }
    }
}

/**
 * 4-byte masking key packed as UInt for zero-allocation access.
 */
@JvmInline
value class WsMaskingKey(
    val raw: UInt,
) {
    companion object {
        /** Wire size of a masking key: always 4 bytes. */
        const val SIZE = 4
    }
}

/**
 * Computes the wire size of a [WsFrameHeader].
 *
 * The KSP-generated [WsFrameHeaderCodec] doesn't emit `sizeOf`, so we provide it here.
 * Size = 1 (byte1) + length field size + optional 4-byte masking key.
 */
fun headerWireSize(header: WsFrameHeader): Int {
    val lengthSize = WsFrameLengthCodec.sizeOf(header.length) ?: error("Length size must be known")
    val maskSize = if (header.maskingKey != null) WsMaskingKey.SIZE else 0
    return 1 + lengthSize + maskSize
}

/**
 * WebSocket close frame body: 2-byte status code + UTF-8 reason string.
 */
@ProtocolMessage
data class WsCloseBody(
    val statusCode: CloseCode,
    @RemainingBytes val reason: String,
)
