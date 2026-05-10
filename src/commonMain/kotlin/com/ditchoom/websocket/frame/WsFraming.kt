package com.ditchoom.websocket.frame

import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.SuspendingStreamProcessor

/**
 * RFC 6455 frame detector for WebSocket framing.
 *
 * RFC 6455 frames carry their payload length inside the header bytes themselves
 * (byte 2 plus an optional 16- or 64-bit extension), so there is no separate
 * body-length prefix between the dispatcher's discriminator and the variant body.
 * [peekFrameSize] returns the full frame size (header + payload), delegating to
 * [WsFrameHeaderCodec.peekFrameSize] for the variable-length header and adding the
 * payload length parsed from byte 2 + optional extended length bytes. Once a frame
 * has been read into a sized buffer, the variant codec consumes the payload via
 * `@RemainingBytes`.
 */
object WsFraming : FrameDetector {
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        val headerPeek = WsFrameHeaderCodec.peekFrameSize(stream, baseOffset)
        if (headerPeek !is PeekResult.Complete) return headerPeek
        val headerSize = headerPeek.bytes
        if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
        val len7 = stream.peekByte(baseOffset + 1).toInt() and 0x7F
        val payloadLength: Int =
            when (len7) {
                126 -> {
                    if (stream.available() < baseOffset + 4) return PeekResult.NeedsMoreData
                    stream.peekShort(baseOffset + 2).toInt() and 0xFFFF
                }
                127 -> {
                    if (stream.available() < baseOffset + 10) return PeekResult.NeedsMoreData
                    val len64 = stream.peekLong(baseOffset + 2)
                    if (len64 < 0 || len64 > Int.MAX_VALUE) {
                        throw FrameParseException("Payload length out of range: $len64")
                    }
                    len64.toInt()
                }
                else -> len7
            }
        return PeekResult.Complete(headerSize + payloadLength)
    }

    /**
     * Suspending overload mirroring [peekFrameSize] but against a
     * [SuspendingStreamProcessor]. Cannot delegate to [WsFrameHeaderCodec.peekFrameSize]
     * — that codec method takes a non-suspending [StreamProcessor], and the two
     * processor types are sibling interfaces, not subtypes. The header-size walk is
     * fixed (RFC 6455 §5.2) so we hand-roll it: 2 bytes always, +2 if extended-16,
     * +8 if extended-64, +4 if masked.
     */
    suspend fun peekFrameSize(
        stream: SuspendingStreamProcessor,
        baseOffset: Int = 0,
    ): PeekResult {
        if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
        val byte2 = stream.peekByte(baseOffset + 1).toInt()
        val masked = (byte2 and 0x80) != 0
        val len7 = byte2 and 0x7F
        val extLenBytes =
            when (len7) {
                126 -> 2
                127 -> 8
                else -> 0
            }
        val maskBytes = if (masked) 4 else 0
        val headerSize = 2 + extLenBytes + maskBytes
        if (stream.available() < baseOffset + headerSize) return PeekResult.NeedsMoreData

        val payloadLength: Int =
            when (len7) {
                126 -> stream.peekShort(baseOffset + 2).toInt() and 0xFFFF
                127 -> {
                    val len64 = stream.peekLong(baseOffset + 2)
                    if (len64 < 0 || len64 > Int.MAX_VALUE) {
                        throw FrameParseException("Payload length out of range: $len64")
                    }
                    len64.toInt()
                }
                else -> len7
            }
        return PeekResult.Complete(headerSize + payloadLength)
    }
}
